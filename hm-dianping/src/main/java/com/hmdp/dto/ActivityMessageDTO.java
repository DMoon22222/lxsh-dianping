package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ActivityMessageDTO {
    private String id;
    private String type;
    private String title;
    private String content;
    private String image;
    private String targetType;
    private Long targetId;
    private long createdAt;
    private Double distance;
}
