package dev.lexoland.updater.rendering;

import org.spongepowered.include.com.google.common.collect.ImmutableMap;

import javax.swing.*;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.List;

public class UpdateRenderer extends JLabel {

	private static final ImmutableMap<RenderingHints.Key, Object> RENDERING_HINTS = ImmutableMap.<RenderingHints.Key, Object>builder()
			.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP)
			.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
			.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
			.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
			.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
			.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
			.build();

	private static final UpdateWindow WINDOW = UpdateWindow.getInstance();

	private static final RadialGradientPaint GRADIENT_PAINT = new RadialGradientPaint(
			new Point(WINDOW.getWidth() / 2, WINDOW.getHeight() / 6),
			WINDOW.getWidth(),
			new float[] { 0.0f, 0.7f },
			new Color[] { new Color(0x12161A), new Color(0x282B31) }
	);
	private static final RadialGradientPaint ERROR_GRADIENT_PAINT = new RadialGradientPaint(
			new Point(WINDOW.getWidth() / 2, WINDOW.getHeight() / 6),
			WINDOW.getWidth(),
			new float[] { 0.0f, 0.7f },
			new Color[] { new Color(0xDA0000), new Color(0x540B0B) }
	);
	private static final RadialGradientPaint WARN_GRADIENT_PAINT = new RadialGradientPaint(
			new Point(WINDOW.getWidth() / 2, WINDOW.getHeight() / 6),
			WINDOW.getWidth(),
			new float[] { 0.0f, 0.7f },
			new Color[] { new Color(0xDAB600), new Color(0xC45A03) }
	);

	private static final int H2 = WINDOW.getHeight() / 2;

	private static ProgressStage stage;
	private static Text header;

	public static void setHeader(String header) {
		UpdateRenderer.header = new Text(header)
				.addAttribute(TextAttribute.FAMILY, "Minecraft")
				.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)
				.addAttribute(TextAttribute.SIZE, 35)
				.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
	}

	public static void setErrorHeader(String header) {
		UpdateRenderer.header = new Text(header)
				.addAttribute(TextAttribute.FAMILY, "Minecraft")
				.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)
				.addAttribute(TextAttribute.SIZE, 20)
				.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
	}

	@Override
	public void paint(Graphics g1) {
		if(g1 instanceof Graphics2D) {
			Graphics2D g = (Graphics2D) g1;
			g.setRenderingHints(RENDERING_HINTS);
			if(stage != null)
				stage.draw(g);
			WINDOW.repaint();
		}
	}

	public static void error(String s) {
		updateStage(ProgressStage.ERROR);
		setErrorHeader(s);
	}

	static void updateStage(ProgressStage stage) {
		if(UpdateRenderer.stage == stage)
			return;
		if(UpdateRenderer.stage != null)
			UpdateRenderer.stage.cleanup();
		UpdateRenderer.stage = stage;
	}

	static ProgressBar getBar(int index) {
		System.out.println("Getting bar " + index + " from " + stage.progressBars.size());
		return stage.progressBars.get(index);
	}

	public enum ProgressStage {
		CREATE_BACKUP(1),
		DOWNLOADING_PACK_META(1),
		DOWNLOADING_PACK_FILES(2),
		EXTRACT_OVERRIDES(1),
		CLEANUP(1),
		CLOSE(0),
		RESTORING_BACKUP(1) {
			@Override
			protected void draw(Graphics2D g) {
				g.setPaint(WARN_GRADIENT_PAINT);
				g.fillRect(0, 0, WINDOW.getWidth(), WINDOW.getHeight());
				drawText(g);
				drawProgressBars(g);
			}
		},
		ERROR(0) {
			@Override
			protected void draw(Graphics2D g) {
				g.setPaint(ERROR_GRADIENT_PAINT);
				g.fillRect(0, 0, WINDOW.getWidth(), WINDOW.getHeight());
				g.setColor(Color.RED);
				header.applyFont(g);
				g.drawString(header.iterator(), (WINDOW.getWidth() - RenderUtils.getTextWidth(g, header)) / 2, H2);
			}
		};

		private final List<ProgressBar> progressBars = new ArrayList<>();

		ProgressStage(int bars) {
			for (int i = 0; i < bars; i++)
				createBar();
		}

		protected void draw(Graphics2D g) {
			drawBackground(g);
			drawText(g);
			drawProgressBars(g);
		}

		private void cleanup() {
			progressBars.clear();
		}

		protected void drawBackground(Graphics2D g) {
			g.setPaint(GRADIENT_PAINT);
			g.fillRect(0, 0, WINDOW.getWidth(), WINDOW.getHeight());
		}

		protected void drawText(Graphics2D g) {
			if(header == null)
				return;
			g.setColor(Color.WHITE);
			header.applyFont(g);
			g.drawString(header.iterator(), (WINDOW.getWidth() - RenderUtils.getTextWidth(g, header)) / 2, H2 - 125);
		}

		protected void drawProgressBars(Graphics2D g) {
			int size = progressBars.size();
			if(size > 0) {
				int singleHeight = WINDOW.getHeight() / 12;
				int spacing = 75;
				int totalHeight = (WINDOW.getHeight() - ((size - 1) * spacing + size * singleHeight)) / 2;

				for (int i = 0; i < size; i++) {
					ProgressBar progressBar = progressBars.get(i);
					progressBar.draw(g, totalHeight + i * (singleHeight + spacing));
				}
			}
		}

		protected void createBar() {
			progressBars.add(new ProgressBar());
		}
	}
}
