package is.hello.commonsense.util;

import rx.functions.Func1;

public class Functions {
    public static <T, U> Func1<T, U> createMapperToValue(final U value) {
        return new Func1<T, U>() {
            @Override
            public U call(T ignored) {
                return value;
            }
        };
    }

    public static <T> Func1<T, Void> createMapperToVoid() {
        return new Func1<T, Void>() {
            @Override
            public Void call(T ignored) {
                return null;
            }
        };
    }
}
