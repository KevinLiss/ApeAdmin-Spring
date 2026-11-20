package com.apeadmin.framework.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

/**
 * 脱敏序列化器：按 @DataMasking 类型分发（ContextualSerializer 拿到注解）。
 */
public class DataMaskingSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private DataMasking.MaskType type;

    public DataMaskingSerializer() {
    }

    public DataMaskingSerializer(DataMasking.MaskType type) {
        this.type = type;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider sp) throws IOException {
        gen.writeString(mask(value, type));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
            throws JsonMappingException {
        if (property != null) {
            DataMasking anno = property.getAnnotation(DataMasking.class);
            if (anno != null && String.class == property.getType().getRawClass()) {
                return new DataMaskingSerializer(anno.value());
            }
            return prov.findValueSerializer(property.getType(), property);
        }
        return this;
    }

    static String mask(String value, DataMasking.MaskType type) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return switch (type) {
            case API_KEY -> value.length() <= 8 ? "****"
                    : value.substring(0, 4) + "****" + value.substring(value.length() - 4);
            case PHONE -> value.length() < 7 ? "****" + value.substring(Math.max(0, value.length() - 4))
                    : value.substring(0, 3) + "****" + value.substring(value.length() - 4);
            case EMAIL -> {
                int at = value.indexOf('@');
                yield at <= 1 ? "****" + value.substring(Math.max(0, at))
                        : value.charAt(0) + "****" + value.substring(at);
            }
            case ALL -> "****";
        };
    }
}
