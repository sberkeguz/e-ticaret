package com.sarikaya.e_ticaret.catalog.dto;

import lombok.Data;

@Data
public class UpdateCategoryRequest {
    String Name;
    Long  ParentId;
}