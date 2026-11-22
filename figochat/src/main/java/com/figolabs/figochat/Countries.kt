package com.figolabs.figochat

import java.text.Collator
import java.util.Locale

/** A country for the phone field of the details form. */
internal data class FigoCountry(
    /** ISO 3166-1 alpha-2, e.g. NG. */
    val code: String,
    /** Dialing code without the +, e.g. 234. */
    val dial: String,
    /** Name in the device language (English when the device has no name for it). */
    val name: String,
) {
    /** The regional-indicator flag emoji. */
    val flag: String
        get() = buildString { code.uppercase().forEach { appendCodePoint(0x1F1E6 + (it - 'A')) } }

    /** Search by name (either language), code or dialing code; a leading + on the query is ignored. */
    fun matches(query: String, englishName: String = name): Boolean {
        val q = query.trim().removePrefix("+").trim()
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) || englishName.contains(q, ignoreCase = true) ||
            code.equals(q, ignoreCase = true) || dial.startsWith(q)
    }
}

internal object FigoCountries {
    // code|dial|English name, from scripts/countries.json
    private const val DATA = """
    AF|93|Afghanistan
    AX|358|Åland Islands
    AL|355|Albania
    DZ|213|Algeria
    AS|1684|American Samoa
    AD|376|Andorra
    AO|244|Angola
    AI|1264|Anguilla
    AG|1268|Antigua & Barbuda
    AR|54|Argentina
    AM|374|Armenia
    AW|297|Aruba
    AU|61|Australia
    AT|43|Austria
    AZ|994|Azerbaijan
    BS|1242|Bahamas
    BH|973|Bahrain
    BD|880|Bangladesh
    BB|1246|Barbados
    BY|375|Belarus
    BE|32|Belgium
    BZ|501|Belize
    BJ|229|Benin
    BM|1441|Bermuda
    BT|975|Bhutan
    BO|591|Bolivia
    BA|387|Bosnia & Herzegovina
    BW|267|Botswana
    BR|55|Brazil
    IO|246|British Indian Ocean Territory
    VG|1284|British Virgin Islands
    BN|673|Brunei
    BG|359|Bulgaria
    BF|226|Burkina Faso
    BI|257|Burundi
    KH|855|Cambodia
    CM|237|Cameroon
    CA|1|Canada
    CV|238|Cape Verde
    KY|1345|Cayman Islands
    CF|236|Central African Republic
    TD|235|Chad
    CL|56|Chile
    CN|86|China
    CO|57|Colombia
    KM|269|Comoros
    CG|242|Congo - Brazzaville
    CD|243|Congo - Kinshasa
    CK|682|Cook Islands
    CR|506|Costa Rica
    CI|225|Côte d’Ivoire
    HR|385|Croatia
    CU|53|Cuba
    CW|599|Curaçao
    CY|357|Cyprus
    CZ|420|Czechia
    DK|45|Denmark
    DJ|253|Djibouti
    DM|1767|Dominica
    DO|1|Dominican Republic
    EC|593|Ecuador
    EG|20|Egypt
    SV|503|El Salvador
    GQ|240|Equatorial Guinea
    ER|291|Eritrea
    EE|372|Estonia
    SZ|268|Eswatini
    ET|251|Ethiopia
    FK|500|Falkland Islands
    FO|298|Faroe Islands
    FJ|679|Fiji
    FI|358|Finland
    FR|33|France
    GF|594|French Guiana
    PF|689|French Polynesia
    GA|241|Gabon
    GM|220|Gambia
    GE|995|Georgia
    DE|49|Germany
    GH|233|Ghana
    GI|350|Gibraltar
    GR|30|Greece
    GL|299|Greenland
    GD|1473|Grenada
    GP|590|Guadeloupe
    GU|1671|Guam
    GT|502|Guatemala
    GG|44|Guernsey
    GN|224|Guinea
    GW|245|Guinea-Bissau
    GY|592|Guyana
    HT|509|Haiti
    HN|504|Honduras
    HK|852|Hong Kong SAR China
    HU|36|Hungary
    IS|354|Iceland
    IN|91|India
    ID|62|Indonesia
    IR|98|Iran
    IQ|964|Iraq
    IE|353|Ireland
    IM|44|Isle of Man
    IL|972|Israel
    IT|39|Italy
    JM|1876|Jamaica
    JP|81|Japan
    JE|44|Jersey
    JO|962|Jordan
    KZ|7|Kazakhstan
    KE|254|Kenya
    KI|686|Kiribati
    XK|383|Kosovo
    KW|965|Kuwait
    KG|996|Kyrgyzstan
    LA|856|Laos
    LV|371|Latvia
    LB|961|Lebanon
    LS|266|Lesotho
    LR|231|Liberia
    LY|218|Libya
    LI|423|Liechtenstein
    LT|370|Lithuania
    LU|352|Luxembourg
    MO|853|Macao SAR China
    MG|261|Madagascar
    MW|265|Malawi
    MY|60|Malaysia
    MV|960|Maldives
    ML|223|Mali
    MT|356|Malta
    MH|692|Marshall Islands
    MQ|596|Martinique
    MR|222|Mauritania
    MU|230|Mauritius
    YT|262|Mayotte
    MX|52|Mexico
    FM|691|Micronesia
    MD|373|Moldova
    MC|377|Monaco
    MN|976|Mongolia
    ME|382|Montenegro
    MS|1664|Montserrat
    MA|212|Morocco
    MZ|258|Mozambique
    MM|95|Myanmar (Burma)
    NA|264|Namibia
    NR|674|Nauru
    NP|977|Nepal
    NL|31|Netherlands
    NC|687|New Caledonia
    NZ|64|New Zealand
    NI|505|Nicaragua
    NE|227|Niger
    NG|234|Nigeria
    NU|683|Niue
    KP|850|North Korea
    MK|389|North Macedonia
    MP|1670|Northern Mariana Islands
    NO|47|Norway
    OM|968|Oman
    PK|92|Pakistan
    PW|680|Palau
    PS|970|Palestinian Territories
    PA|507|Panama
    PG|675|Papua New Guinea
    PY|595|Paraguay
    PE|51|Peru
    PH|63|Philippines
    PL|48|Poland
    PT|351|Portugal
    PR|1|Puerto Rico
    QA|974|Qatar
    RE|262|Réunion
    RO|40|Romania
    RU|7|Russia
    RW|250|Rwanda
    WS|685|Samoa
    SM|378|San Marino
    ST|239|São Tomé & Príncipe
    SA|966|Saudi Arabia
    SN|221|Senegal
    RS|381|Serbia
    SC|248|Seychelles
    SL|232|Sierra Leone
    SG|65|Singapore
    SX|1721|Sint Maarten
    SK|421|Slovakia
    SI|386|Slovenia
    SB|677|Solomon Islands
    SO|252|Somalia
    ZA|27|South Africa
    KR|82|South Korea
    SS|211|South Sudan
    ES|34|Spain
    LK|94|Sri Lanka
    BL|590|St. Barthélemy
    SH|290|St. Helena
    KN|1869|St. Kitts & Nevis
    LC|1758|St. Lucia
    MF|590|St. Martin
    PM|508|St. Pierre & Miquelon
    VC|1784|St. Vincent & Grenadines
    SD|249|Sudan
    SR|597|Suriname
    SE|46|Sweden
    CH|41|Switzerland
    SY|963|Syria
    TW|886|Taiwan
    TJ|992|Tajikistan
    TZ|255|Tanzania
    TH|66|Thailand
    TL|670|Timor-Leste
    TG|228|Togo
    TK|690|Tokelau
    TO|676|Tonga
    TT|1868|Trinidad & Tobago
    TN|216|Tunisia
    TR|90|Türkiye
    TM|993|Turkmenistan
    TC|1649|Turks & Caicos Islands
    TV|688|Tuvalu
    VI|1340|U.S. Virgin Islands
    UG|256|Uganda
    UA|380|Ukraine
    AE|971|United Arab Emirates
    GB|44|United Kingdom
    US|1|United States
    UY|598|Uruguay
    UZ|998|Uzbekistan
    VU|678|Vanuatu
    VA|39|Vatican City
    VE|58|Venezuela
    VN|84|Vietnam
    WF|681|Wallis & Futuna
    YE|967|Yemen
    ZM|260|Zambia
    ZW|263|Zimbabwe
"""

    private val english: Map<String, String> by lazy {
        DATA.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.associate { line ->
            val (code, _, name) = line.split('|', limit = 3)
            code to name
        }
    }

    /** Every country with its dialing code, sorted by name in the device language. */
    val all: List<FigoCountry> by lazy {
        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale)
        DATA.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val (code, dial, name) = line.split('|', limit = 3)
            val local = runCatching { Locale("", code).getDisplayCountry(locale) }.getOrNull()
                ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            FigoCountry(code, dial, local ?: name)
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }.toList()
    }

    fun byCode(code: String?): FigoCountry? = code?.trim()?.uppercase()?.let { c -> all.firstOrNull { it.code == c } }

    fun englishName(country: FigoCountry): String = english[country.code] ?: country.name

    /** Countries matching [query] (name, code or dialing code). */
    fun search(query: String): List<FigoCountry> = all.filter { it.matches(query, englishName(it)) }

    /** The country to preselect: the one given (ISO code, any case) when it is known, else Nigeria. */
    fun default(preferred: String?): FigoCountry = byCode(preferred) ?: byCode(DEFAULT_CODE)!!

    const val DEFAULT_CODE = "NG"

    /** +{dial}{number}, dropping leading trunk zeros and anything that isn't a digit; "" when no digits. */
    fun internationalNumber(country: FigoCountry, local: String): String {
        val digits = local.filter { it in '0'..'9' }.trimStart('0')
        return if (digits.isEmpty()) "" else "+${country.dial}$digits"
    }
}
