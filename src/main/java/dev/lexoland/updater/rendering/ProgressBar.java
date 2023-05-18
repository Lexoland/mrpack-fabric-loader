package dev.lexoland.updater.rendering;

import dev.lexoland.updater.Lazy;

import javax.xml.crypto.Data;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.function.Supplier;

public class ProgressBar {

	private static final float STROKE_WIDTH = 5.3f;

	private static final UpdateWindow WINDOW = UpdateWindow.getInstance();

	private final int w2Mw2D4;
	private final int height;

	private Lazy<Text> status;
	private Lazy<Text> leftText;
	private Lazy<Text> rightText;

	private float progress = -1;

	public ProgressBar progress(float progress) {
		this.progress = progress;
		return this;
	}

	public ProgressBar status(Lazy<String> status) {
		this.status = Lazy.of(() -> new Text(status.get())
				.addAttribute(TextAttribute.FAMILY, "Minecraft")
				.addAttribute(TextAttribute.SIZE, 20)
				.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_DEMIBOLD));
		return this;
	}

	public ProgressBar leftText(Lazy<String> leftText) {
		this.leftText = Lazy.of(() -> {
			if (leftText.get().isEmpty())
				return null;
			return new Text(leftText.get())
					.addAttribute(TextAttribute.FAMILY, "Minecraft")
					.addAttribute(TextAttribute.SIZE, 14);
		});
		return this;
	}

	public ProgressBar rightText(Lazy<String> rightText) {
		this.rightText = Lazy.of(() -> {
			if (rightText.get().isEmpty())
				return null;
			return new Text(rightText.get())
					.addAttribute(TextAttribute.FAMILY, "Minecraft")
					.addAttribute(TextAttribute.SIZE, 14);
		});
		return this;
	}

	public ProgressBar rightText(Lazy<Long> current, Lazy<Long> total) {
		rightText(Lazy.of(() -> {
			long c = current.get();
			long t = total.get();
			if (t >= 1_000_000)
				return String.format("%.2f MB/%.2f MB", (float) c / 1_000_000, (float) t / 1_000_000);
			if (t > 1_000)
				return String.format("%.2f KB/%.2f KB", (float) c / 1_000, (float) t / 1_000);
			return c + "b/" + t + "b";
		}));
		return this;
	}

	public ProgressBar() {
		this.w2Mw2D4 = WINDOW.getWidth() / 2 - (int) (WINDOW.getWidth() / 1.2 * 0.5);
		this.height = WINDOW.getHeight() / 12;
	}

	public void draw(Graphics2D g, int renderHeight) {
		drawOptionalProgressText(g, renderHeight);
		g.setColor(Color.WHITE);

		if(status != null && status.get() != null) {
			status.get().applyFont(g);
			g.drawString(status.get().iterator(), w2Mw2D4, renderHeight - RenderUtils.getTextHeight(g) / 2);
		}

		if(progress == -1)
			return;
		g.setStroke(new BasicStroke(STROKE_WIDTH - 2));
		g.drawRect(
				w2Mw2D4,
				renderHeight,
				(int) (WINDOW.getWidth() / 1.2),
				height
		);
		g.fillRect(
				(int) (w2Mw2D4 + STROKE_WIDTH),
				(int) (renderHeight + STROKE_WIDTH),
				(int) ((WINDOW.getWidth() / 1.2 - STROKE_WIDTH * 2 + 1) * progress),
				height - (int) (STROKE_WIDTH * 2)
		);
	}

	private void drawOptionalProgressText(Graphics2D g, int renderHeight) {
		if(leftText != null && leftText.get() != null) {
			leftText.get().applyFont(g);
			g.drawString(leftText.get().iterator(), w2Mw2D4, renderHeight + RenderUtils.getTextHeight(g) + 30);
		}
		if(rightText != null && rightText.get() != null) {
			rightText.get().applyFont(g);
			g.drawString(rightText.get().iterator(), WINDOW.getWidth() - w2Mw2D4 - RenderUtils.getTextWidth(g, rightText.get()), renderHeight + RenderUtils.getTextHeight(g) + 30);
		}
	}
}
