package com.cleanyourcar.launcher.compat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.cleanyourcar.launcher.compat.ProfileFormatException.Reason;

/** Strict, bounded parser for version 1 device profiles. Unknown fields are always rejected. */
public final class ProfileCodec {

    public static final int MAX_PROFILE_BYTES = 64 * 1024;
    public static final int MAX_PROTECTED_PACKAGES = 256;
    public static final int MAX_ID_BYTES = 128;
    public static final int MAX_PROPERTY_BYTES = 512;
    public static final int MAX_COMPONENT_BYTES = 512;
    public static final int MAX_FIRMWARE_BYTES = 512;
    public static final int MAX_NOTES_BYTES = 4096;
    public static final int MAX_REFERENCES = 16;
    public static final int MAX_REFERENCE_BYTES = 2048;

    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "\\.?[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private static final Set<String> ROOT_FIELDS = fields("schemaVersion", "profileId", "revision",
            "minAppVersionCode", "platform", "adapterId", "match", "roleDefaults", "featureClaims",
            "protectedPackages", "evidence");
    private static final Set<String> MATCH_FIELDS = fields("sdkMin", "sdkMax", "buildProperties");
    private static final Set<String> ROLE_FIELDS = fields("packageName", "componentName", "strategyId");
    private static final Set<String> EVIDENCE_FIELDS =
            fields("classification", "firmware", "notes", "references");

    private ProfileCodec() {
    }

    public static DeviceProfile parse(String json, int appVersionCode) throws ProfileFormatException {
        if (json == null) {
            throw new ProfileFormatException(Reason.MALFORMED, "profile is empty");
        }
        if (json.length() > MAX_PROFILE_BYTES || utf8Length(json) > MAX_PROFILE_BYTES) {
            throw new ProfileFormatException(Reason.TOO_LARGE,
                    "profile exceeds " + MAX_PROFILE_BYTES + " UTF-8 bytes");
        }
        JSONObject root = asObject(readValue(json), "profile");

        int schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != DeviceProfile.SCHEMA_VERSION) {
            throw new ProfileFormatException(Reason.UNSUPPORTED_SCHEMA,
                    "schemaVersion " + schemaVersion + " is not supported");
        }
        int minAppVersionCode = requirePositiveInt(root, "minAppVersionCode");
        if (minAppVersionCode > appVersionCode) {
            throw new ProfileFormatException(Reason.APP_TOO_OLD,
                    "profile requires app version " + minAppVersionCode);
        }
        rejectUnknown(root, ROOT_FIELDS, "profile");

        String profileId = requireString(root, "profileId", MAX_ID_BYTES);
        if (!PROFILE_ID.matcher(profileId).matches()) {
            throw invalid("profileId");
        }
        int revision = requirePositiveInt(root, "revision");
        String platform = requireString(root, "platform", MAX_ID_BYTES);
        if (!DeviceProfile.PLATFORMS.contains(platform)) {
            throw invalid("platform");
        }
        String adapterId = requireString(root, "adapterId", MAX_ID_BYTES);
        if (!DeviceProfile.ADAPTER_IDS.contains(adapterId)) {
            throw new ProfileFormatException(Reason.UNKNOWN_ADAPTER, "adapterId is not built in");
        }

        return new DeviceProfile(schemaVersion, profileId, revision, minAppVersionCode, platform,
                adapterId,
                parseMatch(requireObject(root, "match", "profile")),
                parseRoles(requireObject(root, "roleDefaults", "profile"), adapterId),
                parseClaims(requireObject(root, "featureClaims", "profile")),
                parseProtectedPackages(root),
                parseEvidence(requireObject(root, "evidence", "profile")));
    }

    private static DeviceProfile.Match parseMatch(JSONObject match) throws ProfileFormatException {
        rejectUnknown(match, MATCH_FIELDS, "match");
        int sdkMin = requirePositiveInt(match, "sdkMin");
        int sdkMax = requirePositiveInt(match, "sdkMax");
        if (sdkMax < sdkMin) {
            throw invalid("match.sdkMax");
        }
        JSONObject properties = requireObject(match, "buildProperties", "match");
        rejectUnknown(properties, DeviceProfile.BUILD_PROPERTY_KEYS, "match.buildProperties");
        Map<String, String> buildProperties = new LinkedHashMap<>();
        for (String key : sortedKeys(properties)) {
            buildProperties.put(key, requireString(properties, key, MAX_PROPERTY_BYTES));
        }
        return new DeviceProfile.Match(sdkMin, sdkMax, buildProperties);
    }

    private static Map<String, DeviceProfile.RoleDefault> parseRoles(JSONObject roles,
            String adapterId) throws ProfileFormatException {
        rejectUnknown(roles, DeviceProfile.ROLE_KEYS, "roleDefaults");
        Map<String, DeviceProfile.RoleDefault> result = new LinkedHashMap<>();
        for (String role : sortedKeys(roles)) {
            String path = "roleDefaults." + role;
            JSONObject entry = requireObject(roles, role, "roleDefaults");
            rejectUnknown(entry, ROLE_FIELDS, path);
            String packageName = requirePackageName(entry, "packageName");
            String componentName = null;
            if (entry.has("componentName")) {
                componentName = requireString(entry, "componentName", MAX_COMPONENT_BYTES);
                if (!isComponentOf(componentName, packageName)) {
                    throw invalid(path + ".componentName");
                }
            }
            String strategyId = requireString(entry, "strategyId", MAX_ID_BYTES);
            if (!DeviceProfile.STRATEGY_IDS.contains(strategyId)) {
                throw new ProfileFormatException(Reason.UNKNOWN_STRATEGY,
                        path + ".strategyId is not built in");
            }
            if (DeviceProfile.NAVIGATION_STRATEGY_IDS.contains(strategyId)
                    && !DeviceProfile.ROLE_NAVIGATION.equals(role)) {
                throw invalid(path + ".strategyId");
            }
            if (DeviceProfile.XYAUTO_STRATEGY_IDS.contains(strategyId)
                    && (!DeviceProfile.ADAPTER_XYAUTO.equals(adapterId)
                    || !DeviceProfile.ROLE_EQUALIZER.equals(role))) {
                throw invalid(path + ".strategyId");
            }
            if (DeviceProfile.STRATEGY_LAUNCH_COMPONENT.equals(strategyId) && componentName == null) {
                throw invalid(path + ".componentName");
            }
            result.put(role, new DeviceProfile.RoleDefault(packageName, componentName, strategyId));
        }
        return result;
    }

    private static Map<String, String> parseClaims(JSONObject claims) throws ProfileFormatException {
        rejectUnknown(claims, DeviceProfile.CAPABILITY_IDS, "featureClaims");
        Map<String, String> result = new LinkedHashMap<>();
        for (String capability : sortedKeys(claims)) {
            String value = requireString(claims, capability, MAX_ID_BYTES);
            if (!DeviceProfile.CLAIM_VALUES.contains(value)) {
                throw invalid("featureClaims." + capability);
            }
            result.put(capability, value);
        }
        return result;
    }

    private static List<String> parseProtectedPackages(JSONObject root)
            throws ProfileFormatException {
        JSONArray array = requireArray(root, "protectedPackages");
        if (array.length() > MAX_PROTECTED_PACKAGES) {
            throw new ProfileFormatException(Reason.TOO_MANY_PACKAGES,
                    "at most " + MAX_PROTECTED_PACKAGES + " protected packages are allowed");
        }
        Set<String> packages = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String path = "protectedPackages[" + i + "]";
            String packageName = elementString(array, i, path, MAX_ID_BYTES);
            if (!PACKAGE_NAME.matcher(packageName).matches() || !packages.add(packageName)) {
                throw invalid(path);
            }
        }
        return new ArrayList<>(packages);
    }

    private static DeviceProfile.Evidence parseEvidence(JSONObject evidence)
            throws ProfileFormatException {
        rejectUnknown(evidence, EVIDENCE_FIELDS, "evidence");
        String classification = requireString(evidence, "classification", MAX_ID_BYTES);
        if (!DeviceProfile.EVIDENCE_CLASSIFICATIONS.contains(classification)) {
            throw invalid("evidence.classification");
        }
        String firmware = optionalString(evidence, "firmware", MAX_FIRMWARE_BYTES);
        String notes = optionalString(evidence, "notes", MAX_NOTES_BYTES);
        List<String> references = new ArrayList<>();
        if (evidence.has("references")) {
            JSONArray array = requireArray(evidence, "references");
            if (array.length() > MAX_REFERENCES) {
                throw invalid("evidence.references");
            }
            for (int i = 0; i < array.length(); i++) {
                references.add(elementString(array, i, "evidence.references[" + i + "]",
                        MAX_REFERENCE_BYTES));
            }
        }
        return new DeviceProfile.Evidence(classification, firmware, notes, references);
    }

    private static Object readValue(String json) throws ProfileFormatException {
        String strictInput = StrictJson.validate(json);
        try {
            JSONTokener tokener = new JSONTokener(strictInput);
            Object value = tokener.nextValue();
            if (tokener.nextClean() != 0) {
                throw new ProfileFormatException(Reason.MALFORMED, "unexpected trailing content");
            }
            return value;
        } catch (JSONException e) {
            throw new ProfileFormatException(Reason.MALFORMED, "profile is not valid JSON");
        }
    }

    /** Strict syntax/duplicate-key pass before the platform JSON object model is used. */
    private static final class StrictJson {
        private final String text;
        private final List<Integer> negativeZeroOffsets = new ArrayList<>();
        private int index;

        private StrictJson(String text) {
            this.text = text;
        }

        static String validate(String text) throws ProfileFormatException {
            StrictJson parser = new StrictJson(text);
            parser.skipWhitespace();
            parser.value(0);
            parser.skipWhitespace();
            if (parser.index != text.length()) {
                throw malformed();
            }
            if (parser.negativeZeroOffsets.isEmpty()) return text;
            StringBuilder normalized = new StringBuilder(text);
            for (int i = parser.negativeZeroOffsets.size() - 1; i >= 0; i--) {
                normalized.insert(parser.negativeZeroOffsets.get(i) + 2, ".0");
            }
            return normalized.toString();
        }

        private void value(int depth) throws ProfileFormatException {
            if (depth > 100 || index >= text.length()) {
                throw malformed();
            }
            char current = text.charAt(index);
            if (current == '{') {
                object(depth + 1);
            } else if (current == '[') {
                array(depth + 1);
            } else if (current == '"') {
                string();
            } else if (current == 't') {
                literal("true");
            } else if (current == 'f') {
                literal("false");
            } else if (current == 'n') {
                literal("null");
            } else if (current == '-' || isDigit(current)) {
                number();
            } else {
                throw malformed();
            }
        }

        private void object(int depth) throws ProfileFormatException {
            index++;
            skipWhitespace();
            if (take('}')) return;
            Set<String> keys = new HashSet<>();
            while (true) {
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw malformed();
                }
                String key = string();
                if (!keys.add(key)) {
                    throw malformed();
                }
                skipWhitespace();
                require(':');
                skipWhitespace();
                value(depth);
                skipWhitespace();
                if (take('}')) return;
                require(',');
                skipWhitespace();
            }
        }

        private void array(int depth) throws ProfileFormatException {
            index++;
            skipWhitespace();
            if (take(']')) return;
            while (true) {
                value(depth);
                skipWhitespace();
                if (take(']')) return;
                require(',');
                skipWhitespace();
            }
        }

        private String string() throws ProfileFormatException {
            require('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') return result.toString();
                if (current < 0x20) throw malformed();
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (index >= text.length()) throw malformed();
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        result.append(escape);
                        break;
                    case 'b':
                        result.append('\b');
                        break;
                    case 'f':
                        result.append('\f');
                        break;
                    case 'n':
                        result.append('\n');
                        break;
                    case 'r':
                        result.append('\r');
                        break;
                    case 't':
                        result.append('\t');
                        break;
                    case 'u':
                        result.append(unicodeEscape());
                        break;
                    default:
                        throw malformed();
                }
            }
            throw malformed();
        }

        private char unicodeEscape() throws ProfileFormatException {
            if (index + 4 > text.length()) throw malformed();
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char character = text.charAt(index++);
                int digit = character >= '0' && character <= '9' ? character - '0'
                        : character >= 'a' && character <= 'f' ? character - 'a' + 10
                        : character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
                if (digit < 0) throw malformed();
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private void number() throws ProfileFormatException {
            int start = index;
            if (take('-') && index >= text.length()) throw malformed();
            if (take('0')) {
                if (index < text.length() && isDigit(text.charAt(index))) {
                    throw malformed();
                }
            } else {
                if (index >= text.length() || text.charAt(index) < '1'
                        || text.charAt(index) > '9') {
                    throw malformed();
                }
                digits();
            }
            if (take('.')) {
                if (index >= text.length() || !isDigit(text.charAt(index))) throw malformed();
                digits();
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                if (index >= text.length() || !isDigit(text.charAt(index))) throw malformed();
                digits();
            }
            if (index - start == 2 && text.charAt(start) == '-'
                    && text.charAt(start + 1) == '0') {
                negativeZeroOffsets.add(start);
            }
        }

        private void digits() {
            while (index < text.length() && isDigit(text.charAt(index))) index++;
        }

        private void literal(String expected) throws ProfileFormatException {
            if (!text.regionMatches(index, expected, 0, expected.length())) {
                throw malformed();
            }
            index += expected.length();
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) throws ProfileFormatException {
            if (!take(expected)) throw malformed();
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static ProfileFormatException malformed() {
            return new ProfileFormatException(Reason.MALFORMED, "profile is not valid JSON");
        }
    }

    private static void rejectUnknown(JSONObject object, Set<String> allowed, String path)
            throws ProfileFormatException {
        for (String key : sortedKeys(object)) {
            if (!allowed.contains(key)) {
                throw new ProfileFormatException(Reason.UNKNOWN_FIELD,
                        path + " contains unsupported field \"" + truncate(key) + "\"");
            }
        }
    }

    private static Object require(JSONObject object, String key) throws ProfileFormatException {
        if (!object.has(key)) {
            throw new ProfileFormatException(Reason.MISSING_FIELD, key + " is required");
        }
        return object.opt(key);
    }

    private static int requireInt(JSONObject object, String key) throws ProfileFormatException {
        Object value = require(object, key);
        if (!(value instanceof Integer)) {
            throw badType(key, "an integer");
        }
        return (Integer) value;
    }

    private static int requirePositiveInt(JSONObject object, String key)
            throws ProfileFormatException {
        int value = requireInt(object, key);
        if (value < 1) {
            throw invalid(key);
        }
        return value;
    }

    private static String requireString(JSONObject object, String key, int maxBytes)
            throws ProfileFormatException {
        return checkString(require(object, key), key, maxBytes);
    }

    private static String optionalString(JSONObject object, String key, int maxBytes)
            throws ProfileFormatException {
        return object.has(key) ? checkString(object.opt(key), key, maxBytes) : "";
    }

    private static String elementString(JSONArray array, int index, String path, int maxBytes)
            throws ProfileFormatException {
        return checkString(array.opt(index), path, maxBytes);
    }

    private static String checkString(Object value, String path, int maxBytes)
            throws ProfileFormatException {
        if (!(value instanceof String)) {
            throw badType(path, "a string");
        }
        String text = (String) value;
        if (text.isEmpty() || utf8Length(text) > maxBytes) {
            throw invalid(path);
        }
        return text;
    }

    private static String requirePackageName(JSONObject object, String key)
            throws ProfileFormatException {
        String packageName = requireString(object, key, MAX_ID_BYTES);
        if (!PACKAGE_NAME.matcher(packageName).matches()) {
            throw invalid(key);
        }
        return packageName;
    }

    private static JSONObject requireObject(JSONObject object, String key, String parent)
            throws ProfileFormatException {
        return asObject(require(object, key), parent + "." + key);
    }

    private static JSONObject asObject(Object value, String path) throws ProfileFormatException {
        if (!(value instanceof JSONObject)) {
            throw badType(path, "an object");
        }
        return (JSONObject) value;
    }

    private static JSONArray requireArray(JSONObject object, String key)
            throws ProfileFormatException {
        Object value = require(object, key);
        if (!(value instanceof JSONArray)) {
            throw badType(key, "an array");
        }
        return (JSONArray) value;
    }

    private static boolean isComponentOf(String componentName, String packageName) {
        int slash = componentName.indexOf('/');
        if (slash < 0 || !componentName.substring(0, slash).equals(packageName)) {
            return false;
        }
        return CLASS_NAME.matcher(componentName.substring(slash + 1)).matches();
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        Collections.sort(keys);
        return keys;
    }

    private static int utf8Length(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value <= 0x7f) {
                length++;
            } else if (value <= 0x7ff) {
                length += 2;
            } else if (Character.isHighSurrogate(value) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                length += 4;
                i++;
            } else if (Character.isSurrogate(value)) {
                return Integer.MAX_VALUE;
            } else {
                length += 3;
            }
            if (length > MAX_PROFILE_BYTES) return length;
        }
        return length;
    }

    private static String truncate(String text) {
        return text.length() <= 64 ? text : text.substring(0, 64) + "…";
    }

    private static ProfileFormatException badType(String path, String expected) {
        return new ProfileFormatException(Reason.BAD_TYPE, path + " must be " + expected);
    }

    private static ProfileFormatException invalid(String path) {
        return new ProfileFormatException(Reason.INVALID_VALUE, path + " has an invalid value");
    }

    private static Set<String> fields(String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }
}
