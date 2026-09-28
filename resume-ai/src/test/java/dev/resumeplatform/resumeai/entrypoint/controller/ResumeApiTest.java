package dev.resumeplatform.resumeai.entrypoint.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.resumeplatform.resumeai.infra.repository.entity.ChunkEntity;
import dev.resumeplatform.resumeai.support.DatabaseTest;
import dev.resumeplatform.resumeai.support.PdfFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class ResumeApiTest extends DatabaseTest {
    @MockitoBean(name = "factualChatModel")
    OpenAiChatModel chatModel;
    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    MockMvc mvc;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void noNetwork() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("sem LLM no teste"));
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).stream()
                .map(t -> new float[ChunkEntity.EMBEDDING_DIM]).toList());
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void health() throws Exception {
        var response = mvc.perform(get("/health")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void uploadListDownloadDeleteLifecycle() throws Exception {
        byte[] pdf = PdfFixtures.pdf("Curriculo API, email api.contrato@example.com");
        when(storage.fetch(anyString(), anyString())).thenReturn(Optional.of(pdf));

        var created = mvc.perform(multipart("/resumes")
                .file(new MockMultipartFile("files", "contrato.pdf", "application/pdf", pdf))
                .file(new MockMultipartFile("files", "quebrado.pdf", "application/pdf", "não é pdf".getBytes())))
                .andReturn().getResponse();
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode results = json(created).get("results");
        JsonNode ok = results.get(0);
        assertThat(ok.get("filename").asString()).isEqualTo("contrato.pdf");
        assertThat(ok.get("status").asString()).isEqualTo("ingested");
        assertThat(ok.get("duplicate").asBoolean()).isFalse();
        assertThat(ok.has("error")).as("campo nulo continua no JSON, como no Pydantic").isTrue();
        assertThat(ok.get("error").isNull()).isTrue();

        assertThat(results.get(1).get("status").asString()).isEqualTo("failed");
        assertThat(results.get(1).get("document_id").isNull()).isTrue();
        long documentId = ok.get("document_id").asLong();
        long candidateId = ok.get("candidate_id").asLong();

        JsonNode detail = json(mvc.perform(get("/resumes/" + documentId)).andReturn().getResponse());
        assertThat(detail.get("file_hash").asString()).hasSize(64);
        assertThat(detail.get("chunk_count").asLong()).isPositive();
        assertThat(detail.get("candidate").get("email").asString()).isEqualTo("api.contrato@example.com");
        assertThat(detail.get("candidate").get("created_at").asString()).isNotBlank();

        JsonNode list = json(mvc.perform(get("/resumes").param("limit", "500")).andReturn().getResponse());
        assertThat(list.get("total").asLong()).isPositive();

        var download = mvc.perform(get("/candidates/api.contrato@example.com/resume")).andReturn().getResponse();
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(download.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"contrato.pdf\"");

        var renamed = mvc.perform(put("/candidates/" + candidateId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Nome Corrigido\"}")).andReturn().getResponse();
        JsonNode candidate = json(renamed);
        assertThat(candidate.get("name").asString()).isEqualTo("Nome Corrigido");
        assertThat(candidate.get("email").isNull()).as("PUT é substituição total: ausente vira null").isTrue();

        assertThat(mvc.perform(delete("/resumes/" + documentId)).andReturn().getResponse().getStatus()).isEqualTo(204);
        var gone = mvc.perform(get("/resumes/" + documentId)).andReturn().getResponse();
        assertThat(gone.getStatus()).isEqualTo(404);
        assertThat(json(gone).get("detail").asString()).isEqualTo("Documento " + documentId + " não encontrado.");
    }

    @Test
    void errorsMapToTheSameStatusAsFastApi() throws Exception {
        assertThat(mvc.perform(get("/candidates/999999/resume")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(delete("/resumes/999999")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/resumes").param("limit", "0")).andReturn().getResponse().getStatus()).isEqualTo(422);
        assertThat(mvc.perform(get("/resumes").param("offset", "-1")).andReturn().getResponse().getStatus()).isEqualTo(422);
        var noText = mvc.perform(multipart("/resumes/1").file(
                new MockMultipartFile("file", "x.pdf", "application/pdf", PdfFixtures.withoutText()))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                })).andReturn().getResponse();
        assertThat(noText.getStatus()).isIn(404, 422);
    }

    @Test
    void swaggerIsServedAtDocs() throws Exception {
        var response = mvc.perform(get("/docs")).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(200, 302);
        assertThat(mvc.perform(get("/openapi.json")).andReturn().getResponse().getContentAsString())
                .contains("/chat/stream").contains("/candidates/{identifier}/resume");
    }

    @Test
    void corsIsOpen() throws Exception {
        var response = mvc.perform(get("/health").header("Origin", "http://localhost:5173")).andReturn().getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
    }
}
