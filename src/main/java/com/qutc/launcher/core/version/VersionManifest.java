package com.qutc.launcher.core.version;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mojang 官方版本清单 {@code version_manifest_v2.json}。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VersionManifest {

    public Latest latest;
    public List<Entry> versions;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Latest {
        public String release;
        public String snapshot;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        public String id;
        public String type;
        public String url;
        public String sha1;
        public String releaseTime;
        public String time;
        public Integer complianceLevel;

        public boolean isRelease() {
            return "release".equals(type);
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }
    }

    public Entry find(String id) {
        if (id == null || versions == null) {
            return null;
        }
        for (Entry entry : versions) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    public List<Entry> safeVersions() {
        return versions == null ? List.of() : versions;
    }
}
