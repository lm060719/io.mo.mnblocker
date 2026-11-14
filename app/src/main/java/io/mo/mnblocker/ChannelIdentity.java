package io.mo.mnblocker;

/** Android user + owning package; caller packages must never identify a channel. */
final class ChannelIdentity {
    static final int UNKNOWN_USER = -1;
    final String pkg;
    final int userId;

    ChannelIdentity(String pkg, int userId) {
        if (pkg == null || pkg.isEmpty() || userId < 0) {
            throw new IllegalArgumentException("Channel owner requires a package and concrete user");
        }
        this.pkg = pkg;
        this.userId = userId;
    }

    static String key(String pkg, int userId, String channelId) {
        // ':' cannot occur in an Android package, so this cannot alias a legacy pkg|id.
        return "user:" + userId + "|" + pkg + "|" + channelId;
    }

    String key(String channelId) {
        return key(pkg, userId, channelId);
    }
}
