package com.ishitv.urlshortener.shorturl.api;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.InvalidNullException;

/**
 * Accepts only JSON strings. Any other value fails with an {@link InvalidFormatException} carrying the
 * offending JSON value, which the error handler echoes back as Pydantic's {@code "input"} field.
 *
 * <p>Jackson asks a deserializer for two different "no value" cases, which Pydantic also distinguishes:
 * {@link #getNullValue} for an explicit JSON {@code null} (a type error) and {@link #getAbsentValue} for
 * a property that isn't in the body at all (left as null so {@code @NotNull} reports "missing").
 */
public class StrictStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        JsonNode offending = context.readTree(parser);
        throw InvalidFormatException.from(parser, "Input should be a valid string", offending, String.class);
    }

    @Override
    public String getNullValue(DeserializationContext context) {
        throw InvalidNullException.from(context, PropertyName.construct("original_url"),
                context.constructType(String.class));
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
