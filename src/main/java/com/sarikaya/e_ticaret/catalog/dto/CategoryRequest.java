package com.sarikaya.e_ticaret.catalog.dto;

import java.util.List;

public record CategoryRequest(String name, List<Long> parentIds) {
}