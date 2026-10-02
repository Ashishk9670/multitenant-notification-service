package com.example.notification.model;

import jakarta.validation.constraints.Email;

public record Recipients(@Email String email, String phone, String deviceToken) {
    public String forChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> phone;
            case PUSH -> deviceToken;
        };
    }
}
