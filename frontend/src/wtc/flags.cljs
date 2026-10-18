(ns wtc.flags
  "Country flags: the roster's IOC codes -> the ISO 3166 alpha-2 codes of the
  SVGs in public/flags (from flag-icons 7.5.0, MIT, 4x3). AIN, the neutral
  athletes, has no flag.")

(def iso2
  {"ALG" "dz" "ARG" "ar" "AUS" "au" "AUT" "at" "BEL" "be" "BER" "bm" "BOL" "bo"
   "BRA" "br" "BRU" "bn" "BUL" "bg" "CAN" "ca" "CHI" "cl" "COL" "co" "CRC" "cr"
   "CUB" "cu" "CZE" "cz" "CIV" "ci" "DEN" "dk" "DOM" "do" "ECU" "ec" "EST" "ee"
   "FIN" "fi" "FRA" "fr" "GER" "de" "GRE" "gr" "GUA" "gt" "HUN" "hu" "ISL" "is"
   "IND" "in" "IRL" "ie" "ISR" "il" "ITA" "it" "JPN" "jp" "KEN" "ke" "LAT" "lv"
   "LTU" "lt" "MAS" "my" "MLT" "mt" "MRI" "mu" "MEX" "mx" "MAR" "ma" "NAM" "na"
   "NEP" "np" "NED" "nl" "NZL" "nz" "NOR" "no" "PAK" "pk" "PAR" "py" "PHI" "ph"
   "POL" "pl" "POR" "pt" "ROU" "ro" "SGP" "sg" "SVK" "sk" "SLO" "si" "RSA" "za"
   "ESP" "es" "SWE" "se" "SUI" "ch" "TPE" "tw" "TJK" "tj" "TUR" "tr" "UKR" "ua"
   "GBR" "gb" "USA" "us" "URU" "uy" "VEN" "ve" "ZAM" "zm"})

(defn flag
  "The flag of an IOC code, or an empty frame of the same size if it has none.
  Decorative: the name or code always follows it."
  [code]
  (if-let [c (iso2 code)]
    [:img.flag {:src (str "/flags/" c ".svg") :alt "" :width 20 :height 15 :loading "lazy"}]
    [:span.flag.none {:aria-hidden true}]))
