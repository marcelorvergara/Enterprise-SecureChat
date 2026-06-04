package com.enterprise.securechat.conversation;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_sub", nullable = false)
    private String userSub;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Conversation() {}

    public Conversation(String userSub) {
        this.userSub = userSub;
    }

    public UUID getId() { return id; }
    public String getUserSub() { return userSub; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
