package com.cleanyourcar.launcher.integration;

/**
 * Immutable media state as last reported by a head-unit adapter. Every field starts unknown and
 * changes only when the adapter receives a matching signal; commands never update it.
 */
public final class MediaState {

    public enum Playback { UNKNOWN, PLAYING, PAUSED }

    public enum Connection { UNKNOWN, CONNECTED, DISCONNECTED }

    public enum Source { BLUETOOTH, LOCAL }

    /** Reverse gear state; UNKNOWN until the adapter receives a trustworthy signal. */
    public enum ReverseState { UNKNOWN, ACTIVE, INACTIVE }

    private static final MediaState UNKNOWN_STATE =
            new MediaState(null, null, Playback.UNKNOWN, Connection.UNKNOWN, null);

    /** Track title, or null when not reported. */
    public final String title;
    /** Track artist, or null when not reported. */
    public final String artist;
    public final Playback playback;
    public final Connection connection;
    /** Active source, or null when not reported. */
    public final Source source;

    public MediaState(String title, String artist, Playback playback, Connection connection,
            Source source) {
        if (playback == null || connection == null) {
            throw new IllegalArgumentException("playback and connection are required");
        }
        this.title = title;
        this.artist = artist;
        this.playback = playback;
        this.connection = connection;
        this.source = source;
    }

    public static MediaState unknown() {
        return UNKNOWN_STATE;
    }

    public MediaState withMetadata(String newTitle, String newArtist) {
        return new MediaState(newTitle, newArtist, playback, connection, source);
    }

    public MediaState withPlayback(Playback newPlayback) {
        return new MediaState(title, artist, newPlayback, connection, source);
    }

    public MediaState withConnection(Connection newConnection) {
        return new MediaState(title, artist, playback, newConnection, source);
    }

    public MediaState withSource(Source newSource) {
        return new MediaState(title, artist, playback, connection, newSource);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MediaState)) {
            return false;
        }
        MediaState other = (MediaState) o;
        return equal(title, other.title) && equal(artist, other.artist)
                && playback == other.playback && connection == other.connection
                && source == other.source;
    }

    @Override
    public int hashCode() {
        int result = title == null ? 0 : title.hashCode();
        result = 31 * result + (artist == null ? 0 : artist.hashCode());
        result = 31 * result + playback.hashCode();
        result = 31 * result + connection.hashCode();
        result = 31 * result + (source == null ? 0 : source.hashCode());
        return result;
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
