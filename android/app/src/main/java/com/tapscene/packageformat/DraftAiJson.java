package com.tapscene.packageformat;

/** Shared bounded scalar checks for private editable plans; never executes package data. */
public final class DraftAiJson {
    private DraftAiJson() {}
    public static void validate(byte[] bytes, int maxBytes) { StrictJson.parse(bytes, maxBytes); }
    public static void validateText(String text) { StrictJson.validUnicode(text); }
}
