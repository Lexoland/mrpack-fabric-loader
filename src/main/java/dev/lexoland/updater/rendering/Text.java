package dev.lexoland.updater.rendering;

import java.awt.*;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;

public class Text {
	private final String text;
	private final AttributedString attributedString;

	public Text(String text) {
		this.text = text;
		this.attributedString = new AttributedString(text);
	}

	public Text addAttribute(AttributedCharacterIterator.Attribute key, Object value) {
		attributedString.addAttribute(key, value);
		return this;
	}

	public Text addAttribute(AttributedCharacterIterator.Attribute key, Object value, int start, int end) {
		attributedString.addAttribute(key, value, start, end);
		return this;
	}

	public void applyFont(Graphics2D g) {
		g.setFont(Font.getFont(iterator().getAttributes()));
	}

	public String text() {
		return text;
	}

	public AttributedCharacterIterator iterator() {
		return attributedString.getIterator();
	}
}