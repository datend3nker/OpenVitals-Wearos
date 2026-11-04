package tech.mmarca.openvitals.healthconnect

/**
 * The package whose sleep wins when two apps recorded the same night, or null to let the richer
 * session win. Health Connect keeps the user's own priority list to itself, so this is the app's
 * own setting. Read on each merge, so a change shows at once.
 */
interface PreferredSleepSource {
    var preferredSleepSource: String?
}
