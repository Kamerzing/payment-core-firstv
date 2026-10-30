package com.example.payments2.payment_core2.operations;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class StrictStringDeserializer extends ValueDeserializer<String> {
	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return context.reportInputMismatch(String.class, "A decimal string is required.");
		}
		return parser.getString();
	}
}
