package dev.lexoland.updating.rendering.utils;

public enum Direction {
	NORTH(0, 1),
	EAST(1, 0),
	SOUTH(0, -1),
	WEST(-1, 0);

	private final int x;
	private final int y;

	Direction(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public Direction next() {
		return values()[(ordinal() + 1) % values().length];
	}
}