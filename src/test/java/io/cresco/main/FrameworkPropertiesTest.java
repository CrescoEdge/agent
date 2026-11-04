package io.cresco.main;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Constants;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The launcher's Felix framework properties: no remote repository, nothing fetched over plain HTTP. */
class FrameworkPropertiesTest {

    @Test
    void noRemoteRepositoryAndNoPlainHttpUrl() {
        for (String loc : new String[]{null, "/tmp/cresco-data"}) {
            Map<String, Object> p = HostApplication.frameworkProperties(loc);
            assertFalse(p.containsKey("obr.repository.url"), "no OBR repository");
            for (Map.Entry<String, Object> e : p.entrySet()) {
                assertFalse(String.valueOf(e.getValue()).toLowerCase().contains("http://"), e.getKey() + " is a plain-HTTP URL");
            }
        }
    }

    @Test
    void theStorageFollowsTheDataLocation() {
        assertEquals("cresco-data/felix-cache", HostApplication.frameworkProperties(null).get(Constants.FRAMEWORK_STORAGE));
        assertTrue(String.valueOf(HostApplication.frameworkProperties("/tmp/cd").get(Constants.FRAMEWORK_STORAGE)).endsWith("/tmp/cd/felix-cache"));
        assertEquals(Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT, HostApplication.frameworkProperties(null).get(Constants.FRAMEWORK_STORAGE_CLEAN));
    }
}
