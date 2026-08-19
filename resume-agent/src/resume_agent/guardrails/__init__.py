"""Regras que limitam o que entra na base e o que o agente aceita responder.

- `injection`: roda na ingestão, sobre o texto do PDF. O currículo é a única
  entrada não confiável do sistema e tem funil único (`ingestion_service`);
  bloqueado ali, o payload nunca chega ao Postgres. Custo O(1) por documento,
  não por consulta.
- `discrimination`: middleware do agente, sobre a pergunta do usuário. A entrada
  é o recrutador, e o risco não é sequestro do prompt: é a triagem usar atributo
  que a lei protege.
"""
