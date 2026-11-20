package com.swapp.category;

public record CategoryDto(Long id, String name, String slug, String icon) {

    public static CategoryDto from(Category c) {
        return new CategoryDto(c.getId(), c.getName(), c.getSlug(), c.getIcon());
    }
}
