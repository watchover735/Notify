package com.notify.download.stream

/**
 * Smart query routing policy to determine which providers should participate in the parallel race.
 *
 * Prevents unnecessary network calls (e.g. to JioSaavn for non-Indian catalog tracks)
 * by checking for Indic scripts and recognized Indian artist signatures.
 */
object ProviderRoutingPolicy {

    // Common Unicode blocks for Indic scripts:
    // Devanagari (Hindi, Marathi, etc.): \u0900-\u097F
    // Gurmukhi (Punjabi): \u0A00-\u0A7F
    // Bengali/Assamese: \u0980-\u09FF
    // Gujarati: \u0A80-\u0AFF
    // Tamil: \u0B80-\u0BFF
    // Telugu: \u0C00-\u0C7F
    // Kannada: \u0C80-\u0CFF
    // Malayalam: \u0D00-\u0D7F
    private val INDIC_SCRIPT_REGEX = Regex("[\\u0900-\\u0D7F]")

    // Configurable dictionary of popular Indian / South-Asian music artists and genres
    private val KNOWN_INDIAN_ARTISTS = setOf(
        // Haryanvi artists & regional keywords
        "masoom sharma", "masoom",
        "renuka panwar", "amit saini rohtakiya", "amit saini",
        "gulzaar chhaniwala", "sumit goswami", "diler kharkiya",
        "sapna choudhary", "sapna", "dhanda nyoliwala",
        "khasa aala chahar", "kd", "kulbir danoda",
        "raju punjabi", "vijay varma", "aman jaji",
        "ruchika jangid", "bintu pabra", "komal choudhary",
        "manisha sharma", "ajay hooda",
        "haryanvi", "2 numbari", "numbari", "ragni",

        // Punjabi artists & keywords
        "arijit", "arijit singh",
        "diljit", "diljit dosanjh",
        "sidhu", "sidhu moose wala", "sidhu moosewala",
        "karan aujla", "ap dhillon", "shubh",
        "ammy virk", "b praak", "jass manak",
        "harrdy sandhu", "guru randhawa", "kaka",
        "jasleen royal", "gurinder gill", "prem dhillon",
        "arjan dhillon", "wazir patar", "chani nattan",
        "korala maan", "jordan sandhu", "amrit maan",
        "mankirt aulakh", "babbu maan", "bohemia",
        "imran khan", "sukhe", "ninja",
        "gurnam bhullar", "tarsem jassar", "sharry maan",
        "kulwinder billa", "rajvir jawanda", "guri",
        "kuldeep manak", "gurdas maan", "surjit bindrakhia",
        "jazzy b", "gippy grewal", "amar singh chamkila",
        "chamkila", "amarjot", "bhangra", "punjabi",

        // Hindi / Bollywood / Pop artists
        "pritam", "neha kakkar", "badshah",
        "shreya ghoshal", "atif aslam", "lata mangeshkar",
        "kishore kumar", "kumar sanu", "alka yagnik",
        "udit narayan", "sonu nigam", "anuv jain",
        "yo yo honey singh", "honey singh",
        "jubin nautiyal", "sachin-jigar", "sachin jigar",
        "vishal-shekhar", "vishal shekhar",
        "a.r. rahman", "ar rahman", "rahman",
        "armaan malik", "darshan raval",
        "sunidhi chauhan", "mohit chauhan",
        "divine", "raftaar", "emiway", "emiway bantai",
        "king", "mc stan", "prateek kuhad",
        "amitabh bhattacharya", "ankit tiwari",
        "mika singh", "tony kakkar",
        "himesh reshammiya", "mithoon", "amaal mallik",
        "tanishk bagchi", "vishal mishra", "zaeden",
        "ritviz", "ash king", "shaan", "kk",
        "krishnakumar kunnath", "papon", "lucky ali",
        "adnan sami", "jagjit singh", "nusrat fateh ali khan",
        "rahat fateh ali khan", "ghulam ali",
        "bollywood", "desi", "ghazal", "qawwali", "sufi", "bhajan",

        // Bhojpuri artists
        "pawan singh", "khesari lal yadav", "khesari",
        "shilpi raj", "arvind akela kallu", "pramod premi yadav",
        "samar singh", "ankush raja", "gunjan singh",
        "neelkamal singh", "ritesh pandey", "dinesh lal yadav",
        "nirahua", "kalpana patowary", "manoj tiwari", "bhojpuri",

        // South-Indian artists & keywords
        "anirudh ravichander", "anirudh", "sid sriram",
        "devi sri prasad", "dsp", "thaman",
        "santhosh narayanan", "harris jayaraj",
        "ilaiyaraaja", "spb", "s. p. balasubrahmanyam",
        "k. s. chithra", "chithra", "k. j. yesudas", "yesudas",
        "vijay prakash", "chinmayi", "sujatha",
        "hariharan", "unni menon", "karthik", "naresh iyer",
        "tollywood", "kollywood"
    )

    // Compiled word-boundary regex for accurate matching (avoids false substrings like 'bohemia' in 'bohemian')
    private val ARTIST_REGEX by lazy {
        val pattern = KNOWN_INDIAN_ARTISTS
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE)
    }

    /**
     * Evaluates whether JioSaavn should participate in the parallel resolution race,
     * returning the boolean decision and the diagnostic reason string.
     *
     * @return Pair of `(shouldInclude, reason)` where reason is:
     *         `matched_script`, `matched_artist`, or `no_match`.
     */
    fun evaluateJioSaavnRouting(
        query: String,
        title: String? = null,
        artist: String? = null
    ): Pair<Boolean, String> {
        val combined = buildString {
            append(query)
            if (!title.isNullOrBlank()) append(" ").append(title)
            if (!artist.isNullOrBlank()) append(" ").append(artist)
        }

        // 1. Script detection (Devanagari, Gurmukhi, etc.)
        if (INDIC_SCRIPT_REGEX.containsMatchIn(combined)) {
            return Pair(true, "matched_script")
        }

        // 2. Keyword & artist name detection (word-boundary matched)
        if (ARTIST_REGEX.containsMatchIn(combined)) {
            return Pair(true, "matched_artist")
        }

        return Pair(false, "no_match")
    }

    /**
     * Determines whether JioSaavn should participate in the parallel resolution race.
     *
     * @param query Full search or track query string.
     * @param title Track title (if available).
     * @param artist Track artist (if available).
     * @return `true` if Indic script or a known Indian artist is detected.
     */
    fun shouldIncludeJioSaavn(
        query: String,
        title: String? = null,
        artist: String? = null
    ): Boolean = evaluateJioSaavnRouting(query, title, artist).first
}
