package com.example.payments2.payment_core2.operations;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class StrictIntegerDeserializer extends ValueDeserializer<Integer> {
	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
			return context.reportInputMismatch(Integer.class, "An integer JSON number is required.");
		}
		return parser.getIntValue();
	}
}
