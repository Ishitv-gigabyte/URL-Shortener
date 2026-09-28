package com.ishitv.urlshortener.shorturl.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code POST /shorten}. {@code StrictStringDeserializer} makes Jackson behave like Pydantic:
 * {@code 123}, {@code {}} and an explicit {@code null} are type errors, while an <em>absent</em> field
 * reaches {@code @NotNull} and is reported as "missing".
 */
public record ShortenRequest(
        @JsonProperty("original_url")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        @NotNull
        @Size(min = 10, max = 1500)
        String originalUrl) {
}
