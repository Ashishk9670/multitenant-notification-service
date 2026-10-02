package com.example.notification.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Recipients(@Email @Size(max = 254) String email,
                         @Pattern(regexp = "^\\+?[0-9 ()-]{3,20}$", message = "must be a phone number") String phone,
                         @Size(max = 256) String deviceToken) {
    public String forChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> email;
            case SMS -> phone;
            case PUSH -> deviceToken;
        };
    }
}
