package dev.lexoland.updater.rendering.stages;

import com.google.common.collect.ImmutableMap;
import dev.lexoland.updater.Updater;

import java.awt.*;
import java.awt.font.TextAttribute;

public class UpdateStage {

	protected static final Color DEFAULT_PRIMARY_BACKGROUND_COLOR = new Color(0x12161A);
	protected static final Color DEFAULT_SECONDARY_BACKGROUND_COLOR = new Color(0x282B31);

	protected static final float PROGRESS_BAR_STROKE_WIDTH = 5.3f;
	protected static final int PROGRESS_BAR_HEIGHT = 29;
	protected static final int PROGRESS_BAR_WIDTH = 500;
	protected static final Font PROGRESS_BAR_STATUS_FONT = new Font("Minecraft", Font.PLAIN, 20);
	protected static final Font PROGRESS_BAR_SUB_STATUS_FONT = new Font("Minecraft", Font.ITALIC, 20);

	protected static final Font HEADER_FONT = new Font("Minecraft", Font.BOLD, 30).deriveFont(
			ImmutableMap.<TextAttribute, Integer>builder()
					.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)
					.build()
	);

	protected final Updater updater;
	protected final String title;
	protected final Color primaryColor, secondaryColor;
	protected int screenWidth, screenHeight;

	public UpdateStage(Updater updater, String title) {
		this(updater, title, DEFAULT_PRIMARY_BACKGROUND_COLOR, DEFAULT_SECONDARY_BACKGROUND_COLOR);
	}

	public UpdateStage(Updater updater, String title, Color primaryColor, Color secondaryColor) {
		this.updater = updater;
		this.title = title;
		this.primaryColor = primaryColor;
		this.secondaryColor = secondaryColor;
	}

	public void render(Graphics2D graphics) {
		renderGradientBackground(graphics, primaryColor, secondaryColor);
		renderTitle(graphics, title);
	}

	protected void renderGradientBackground(Graphics2D graphics, Color primaryColor, Color secondaryColor) {
		graphics.setPaint(new LinearGradientPaint(
				new Point(0, 0),
				new Point(0, screenHeight),
				new float[]{0.0f, 1f},
				new Color[]{primaryColor, secondaryColor}
		));
		graphics.fillRect(0, 0, screenWidth, screenHeight);
	}

	protected void renderTitle(Graphics2D graphics, String header) {
		graphics.setFont(HEADER_FONT);
		graphics.setColor(Color.WHITE);

		FontMetrics metrics = graphics.getFontMetrics();

		graphics.drawString(
				header,
				(screenWidth - metrics.stringWidth(header)) / 2,
				10 + metrics.getHeight()
		);
	}

	public void setScreenSize(int width, int height) {
		this.screenWidth = width;
		this.screenHeight = height;
	}

	protected int middleY() {
		return screenHeight / 2 + 32;
	}

	protected String downloadProgressFormatted() {
		long current = updater.getDownloaded();
		long total = updater.getDownloadSize();

		if (total >= 1_000_000)
			return String.format("%.2f MB/%.2f MB", (float) current / 1_000_000, (float) total / 1_000_000);
		if (total > 1_000)
			return String.format("%.2f KB/%.2f KB", (float) current / 1_000, (float) total / 1_000);
		return current + "b/" + total + "b";
	}

	protected String downloadSpeedFormatted() {
		long speed = updater.getDownloadSpeed();

		if (speed >= 1_000_000)
			return String.format("%.2f MB/s", (float) speed / 1_000_000);
		if (speed > 1_000)
			return String.format("%.2f KB/s", (float) speed / 1_000);
		return speed + "b/s";
	}

	protected long downloaded() {
		return updater.getDownloaded();
	}

	protected long downloadSize() {
		return updater.getDownloadSize();
	}

	protected int totalEntries() {
		return updater.getTotalEntries();
	}

	protected int currentEntry() {
		return updater.getCurrentEntry();
	}

	protected String entryName() {
		return updater.getEntryName();
	}

	class ProgressBar {

		private long lastTime = System.currentTimeMillis();
		private double smoothedProgress;

		protected void renderProgressBar(
				Graphics2D graphics, int y,
				double progress,
				String status, String leftText, String rightText
		) {
			int x = (screenWidth - PROGRESS_BAR_WIDTH) / 2;

			// sub status
			graphics.setColor(Color.WHITE);
			graphics.setFont(PROGRESS_BAR_SUB_STATUS_FONT);

			if (leftText != null)
				graphics.drawString(
						leftText,
						x, y + graphics.getFontMetrics().getHeight() + PROGRESS_BAR_HEIGHT
				);
			if (rightText != null)
				graphics.drawString(
						rightText,
						x + PROGRESS_BAR_WIDTH - graphics.getFontMetrics().stringWidth(rightText),
						y + graphics.getFontMetrics().getHeight() + PROGRESS_BAR_HEIGHT
				);

			// status
			if (status != null) {
				FontMetrics metrics = graphics.getFontMetrics();

				int statusWidth = 0;
				for (int i = 0; i < status.length(); i++) {
					statusWidth += metrics.charWidth(status.charAt(i));

					if (statusWidth < PROGRESS_BAR_WIDTH)
						continue;

					status = status.substring(0, Math.max(i - 2, 0)) + "...";
					break;
				}

				graphics.setFont(PROGRESS_BAR_STATUS_FONT);
				graphics.drawString(status, x, y - graphics.getFontMetrics().getHeight() / 2);
			}

			// smooth progress
			long now = System.currentTimeMillis();
			if (smoothedProgress >= progress) {
				smoothedProgress = progress;
			} else smoothedProgress += 18 * (progress - smoothedProgress) * (now - lastTime) / 1000.0;
			lastTime = now;

			// progress bar
			graphics.setStroke(new BasicStroke(PROGRESS_BAR_STROKE_WIDTH - 2));

			graphics.drawRect(
					x, y,
					PROGRESS_BAR_WIDTH,
					PROGRESS_BAR_HEIGHT
			);

			graphics.fillRect(
					(int) (x + PROGRESS_BAR_STROKE_WIDTH),
					(int) (y + PROGRESS_BAR_STROKE_WIDTH),
					(int) ((PROGRESS_BAR_WIDTH - PROGRESS_BAR_STROKE_WIDTH * 2 + 1) * smoothedProgress),
					PROGRESS_BAR_HEIGHT - (int) (PROGRESS_BAR_STROKE_WIDTH * 2)
			);
		}
	}
}
