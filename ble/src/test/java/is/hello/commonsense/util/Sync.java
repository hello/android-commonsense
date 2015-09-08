package is.hello.commonsense.util;

import android.support.annotation.NonNull;

import org.hamcrest.Matcher;
import org.junit.Assert;

import java.util.Iterator;

import rx.Observable;
import rx.observables.BlockingObservable;

/**
 * A wrapper around BlockingObservable to make it more or
 * less idiot-proof to use when writing tests.
 * <p/>
 * All operators throw exceptions emitted by the source observable.
 * @param <T>   The type emitted by the Sync wrapper.
 */
public final class Sync<T> implements Iterable<T> {
    /**
     * The wrapped observable.
     */
    private final BlockingObservable<T> observable;


    //region Creation

    /**
     * Wraps an unbounded source observable.
     * <p/>
     * This method <b>does not</b> work PresenterSubject.
     */
    public static <T> Sync<T> wrap(@NonNull Observable<T> source) {
        return new Sync<>(source);
    }


    private Sync(@NonNull Observable<T> source) {
        this.observable = source.toBlocking();
    }

    //endregion


    //region Binding

    /**
     * Returns an iterator that yields any values the wrapped
     * observable has already emitted. <b>This method will
     * not block for values.</b>
     */
    @Override
    public Iterator<T> iterator() {
        return observable.getIterator();
    }

    /**
     * Blocks until the observable completes, then returns the last emitted value.
     */
    public T last() {
        return observable.last();
    }

    //endregion


    //region Assertions

    /**
     * Blocks until the observable errors out.
     * <p/>
     * This method raises an assertion failure if the observable does not fail,
     * or if the error passed out of the observable does not match the given class.
     */
    public <E extends Throwable> void assertThrows(@NonNull Class<E> errorClass) {
        try {
            last();
            Assert.fail("Observable did not fail as expected");
        } catch (Throwable e) {
            if (!errorClass.isAssignableFrom(e.getClass()) &&
                    e.getCause() != null && !errorClass.isAssignableFrom(e.getCause().getClass())) {
                Assert.fail("Unexpected failure '" + e.getClass() + "'");
            }
        }
    }

    /**
     * Blocks until the observable completes, <code>assert</code>ing
     * that the value matches the given matcher.
     * @param matcher The matcher.
     */
    public T assertThat(@NonNull Matcher<? super T> matcher) {
        T last = last();
        Assert.assertThat(last, matcher);
        return last;
    }

    //endregion


    //region Convenience

    /**
     * Shorthand for <code>Sync.wrap(observable).last();</code>.
     *
     * @see #wrap(Observable)
     * @see #last()
     */
    public static <T> T last(@NonNull Observable<T> source) {
        return wrap(source).last();
    }

    //endregion
}
