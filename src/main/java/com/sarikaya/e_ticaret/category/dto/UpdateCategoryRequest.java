package com.sarikaya.e_ticaret.category.dto;

import lombok.Data;

@Data
public class UpdateCategoryRequest {
    String Name;
    Long  ParentId;
}