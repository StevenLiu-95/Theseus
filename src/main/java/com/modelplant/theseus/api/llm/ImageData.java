package com.modelplant.theseus.api.llm;

public record ImageData(String mimeType, String base64) {
    public ImageData {
        mimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        base64 = base64 == null ? "" : base64;
    }
}
