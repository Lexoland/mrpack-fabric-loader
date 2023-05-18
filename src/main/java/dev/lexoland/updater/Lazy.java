package dev.lexoland.updater;

import java.util.function.Supplier;

@FunctionalInterface
public interface Lazy<T> {

	T get();

	static <T> Lazy<T> of(Supplier<T> supplier) {
		return new LazyImpl<>(supplier);
	}

	class LazyImpl<T> implements Lazy<T> {

		private final Supplier<T> supplier;

		private T value;

		public LazyImpl(Supplier<T> supplier) {
			this.supplier = supplier;
		}

		@Override
		public T get() {
			if(value == null)
				value = supplier.get();
			return value;
		}
	}
}
