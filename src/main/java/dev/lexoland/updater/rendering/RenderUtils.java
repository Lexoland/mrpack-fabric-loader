package dev.lexoland.updater.rendering;

import java.awt.*;

public class RenderUtils {

	public static int getTextWidth(Graphics2D g, Text text) {
		return g.getFontMetrics().stringWidth(text.text());
	}

	public static int getTextHeight(Graphics2D g) {
		return g.getFontMetrics().getHeight();
	}

}
