package dev.resumeplatform.resumeai.db;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "candidates")
public class Candidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    private String phone;

    @Generated
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Candidate() {
    }

    public Candidate(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    public void replaceWith(String name, String email, String phone) {
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
