package is.hello.commonsense.util;

import org.junit.Test;

import is.hello.commonsense.CommonSenseTestCase;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class CompatibilityTests extends CommonSenseTestCase {
    private static final String[] BLACKLIST = {"Nexus 4"};

    @Test
    public void isModelBlacklisted() {
        assertThat(Compatibility.isModelBlacklisted(BLACKLIST, "Nexus 4"), is(true));
        assertThat(Compatibility.isModelBlacklisted(BLACKLIST, "nexus 4"), is(true));
        assertThat(Compatibility.isModelBlacklisted(BLACKLIST, "NEXUS 4"), is(true));
        assertThat(Compatibility.isModelBlacklisted(BLACKLIST, "Nexus 5"), is(false));
    }
}
