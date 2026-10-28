package com.youraicqa.app

object MathSymbolsExtra {

    val LIMIT_OPS: Set<String> = setOf(
        "sum", "prod", "coprod", "bigcup", "bigcap", "bigoplus", "bigotimes", "bigodot", "biguplus",
        "bigvee", "bigwedge", "bigsqcup", "lim", "limsup", "liminf", "max", "min", "sup", "inf", "argmax", "argmin"
    )

    val FUNCTIONS: Set<String> = setOf(
        "lim", "limsup", "liminf", "sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "coth",
        "arcsin", "arccos", "arctan", "arccot", "log", "ln", "lg", "exp", "min", "max", "det", "gcd", "lcm",
        "deg", "arg", "sup", "inf", "dim", "ker", "mod", "Pr", "hom", "tr", "rank", "sgn", "Re", "Im", "span",
        "argmax", "argmin", "erf", "var", "cov"
    )

    val ACCENTS: Map<String, Char> = mapOf(
        "acute" to '́', "grave" to '̀', "breve" to '̆', "check" to '̌',
        "mathring" to '̊', "dddot" to '⃛', "overleftarrow" to '⃖', "overleftrightarrow" to '⃡',
        "ddddot" to '⃜'
    )

    val CALLIGRAPHIC: Map<Char, Char> = mapOf(
        'B' to 'ℬ', 'E' to 'ℰ', 'F' to 'ℱ', 'H' to 'ℋ', 'I' to 'ℐ', 'L' to 'ℒ', 'M' to 'ℳ', 'R' to 'ℛ',
        'e' to 'ℯ', 'g' to 'ℊ', 'o' to 'ℴ'
    )

    /** \mathfrak. */
    val FRAKTUR: Map<Char, Char> = mapOf('C' to 'ℭ', 'H' to 'ℌ', 'I' to 'ℑ', 'R' to 'ℜ', 'Z' to 'ℨ')

    val BLACKBOARD: Map<Char, Char> = mapOf(
        'C' to 'ℂ', 'H' to 'ℍ', 'N' to 'ℕ', 'P' to 'ℙ', 'Q' to 'ℚ', 'R' to 'ℝ', 'Z' to 'ℤ',
        'D' to 'ⅅ', 'd' to 'ⅆ', 'e' to 'ⅇ', 'i' to 'ⅈ', 'j' to 'ⅉ',
        'Γ' to 'ℾ', 'Π' to 'ℿ', 'γ' to 'ℽ', 'π' to 'ℼ'
    )

    val SYMBOLS: Map<String, String> = mapOf(
        "dotplus" to "∔", "smallsetminus" to "∖", "divideontimes" to "⋇", "ltimes" to "⋉", "rtimes" to "⋊",
        "leftthreetimes" to "⋋", "rightthreetimes" to "⋌", "boxplus" to "⊞", "boxminus" to "⊟", "boxtimes" to "⊠",
        "boxdot" to "⊡", "circledast" to "⊛", "circledcirc" to "⊚", "circleddash" to "⊝", "odot" to "⊙",
        "oslash" to "⊘", "barwedge" to "⊼", "veebar" to "⊻", "doublebarwedge" to "⩞", "curlywedge" to "⋏",
        "curlyvee" to "⋎", "Cap" to "⋒", "Cup" to "⋓", "amalg" to "⨿", "diamond" to "⋄", "bigtriangleup" to "△",
        "bigtriangledown" to "▽", "triangleleft" to "◁", "triangleright" to "▷", "lhd" to "⊲", "rhd" to "⊳",
        "unlhd" to "⊴", "unrhd" to "⊵", "wr" to "≀", "centerdot" to "·", "intercal" to "⊺", "bigodot" to "⨀",
        "biguplus" to "⨄", "bigsqcup" to "⨆", "bigvee" to "⋁", "bigwedge" to "⋀", "coprod" to "∐",
        "idotsint" to "∫⋯∫", "oiint" to "∯", "oiiint" to "∰", "varointclockwise" to "∲", "ointctrclockwise" to "∳",
        "surd" to "√", "cuberoot" to "∛", "fourthroot" to "∜",
        "lesssim" to "≲", "gtrsim" to "≳", "lessgtr" to "≶", "gtrless" to "≷", "lessapprox" to "⪅",
        "gtrapprox" to "⪆", "preceq" to "⪯", "succeq" to "⪰", "prec" to "≺", "succ" to "≻", "precsim" to "≾",
        "succsim" to "≿", "nprec" to "⊀", "nsucc" to "⊁", "ncong" to "≇", "nsim" to "≁", "napprox" to "≉",
        "nequiv" to "≢", "asymp" to "≍", "bumpeq" to "≏", "Bumpeq" to "≎", "doteqdot" to "≑", "fallingdotseq" to "≒",
        "risingdotseq" to "≓", "eqcirc" to "≖", "circeq" to "≗", "triangleq" to "≜", "coloneqq" to "≔",
        "eqqcolon" to "≕", "approxeq" to "≊", "backsim" to "∽", "backsimeq" to "⋍", "thicksim" to "∼",
        "thickapprox" to "≈", "vdash" to "⊢", "dashv" to "⊣", "vDash" to "⊨", "Vdash" to "⊩", "models" to "⊨",
        "nvdash" to "⊬", "nvDash" to "⊭", "Vvdash" to "⊪", "bowtie" to "⋈", "Join" to "⋈", "smile" to "⌣",
        "frown" to "⌢", "between" to "≬", "pitchfork" to "⋔", "sqsubset" to "⊏", "sqsupset" to "⊐",
        "sqsubseteq" to "⊑", "sqsupseteq" to "⊒", "Subset" to "⋐", "Supset" to "⋑", "subseteqq" to "⫅",
        "supseteqq" to "⫆", "supsetneqq" to "⫌", "subsetneqq" to "⫋", "nsupseteq" to "⊉", "nsubset" to "⊄",
        "nsupset" to "⊅", "notni" to "∌", "owns" to "∋", "ntriangleleft" to "⋪", "ntriangleright" to "⋫",
        "trianglelefteq" to "⊴", "trianglerighteq" to "⊵", "vartriangleleft" to "⊲", "vartriangleright" to "⊳",
        "lll" to "⋘", "ggg" to "⋙", "llless" to "⋘", "gggtr" to "⋙", "leqq" to "≦", "geqq" to "≧",
        "lneq" to "⪇", "gneq" to "⪈", "lneqq" to "≨", "gneqq" to "≩", "eqslantless" to "⪕", "eqslantgtr" to "⪖",
        "shortmid" to "∣", "shortparallel" to "∥", "nparallel" to "∦", "nshortmid" to "∤", "Colon" to "∷",
        "ratio" to "∶", "dotsb" to "⋯", "dotsc" to "…", "dotsm" to "⋯", "dotsi" to "⋯", "dotso" to "…",
        "mathellipsis" to "…", "iddots" to "⋰",
        "complement" to "∁", "Box" to "□", "Diamond" to "◇", "lozenge" to "◊", "blacklozenge" to "⧫",
        "blacktriangle" to "▲", "blacktriangledown" to "▼", "blacktriangleleft" to "◀", "blacktriangleright" to "▶",
        "bigcirc" to "◯", "bigstar" to "★", "spadesuit" to "♠", "clubsuit" to "♣", "heartsuit" to "♡",
        "diamondsuit" to "♢", "flat" to "♭", "natural" to "♮", "sharp" to "♯",
        "AA" to "Å", "angstrom" to "Å", "mho" to "℧", "Finv" to "Ⅎ", "Game" to "⅁", "eth" to "ð",
        "beth" to "ℶ", "gimel" to "ℷ", "daleth" to "ℸ", "aleph" to "ℵ", "digamma" to "ϝ", "varkappa" to "ϰ",
        "backepsilon" to "϶", "epsilon" to "ε", "planck" to "ℎ", "hslash" to "ℏ",
        "naturals" to "ℕ", "integers" to "ℤ", "rationals" to "ℚ", "reals" to "ℝ", "complexes" to "ℂ",
        "primes" to "ℙ",
        "emptyset" to "∅", "ss" to "ß", "ae" to "æ", "AE" to "Æ", "oe" to "œ", "OE" to "Œ",
        "dag" to "†", "ddag" to "‡", "pounds" to "£", "yen" to "¥", "cent" to "¢",
        "textregistered" to "®", "texttrademark" to "™", "textbullet" to "•", "textperthousand" to "‰",
        "perthousand" to "‰", "permil" to "‰", "textpertenthousand" to "‱", "micro" to "µ", "ohm" to "Ω",
        "textohm" to "Ω", "celsius" to "℃", "fahrenheit" to "℉", "textcelsius" to "℃", "numero" to "№",
        "textnumero" to "№", "textminus" to "−", "minus" to "−", "plus" to "+", "textpm" to "±", "textmu" to "µ",
        "differentialD" to "ⅆ", "exponentiale" to "ⅇ", "imaginaryI" to "ⅈ",
        "measuredangle" to "∡", "sphericalangle" to "∢", "rightangle" to "∟", "arcminute" to "′", "arcsecond" to "″",
        "dprime" to "″", "trprime" to "‴", "backprime" to "‵", "ang" to "∠", "diameter" to "⌀", "perpendicular" to "⟂",
        "uparrowbar" to "⤒", "square" to "□", "squared" to "²", "cubed" to "³",
        "longmapsto" to "⟼", "mapsfrom" to "↤", "leftarrowtail" to "↢", "rightarrowtail" to "↣",
        "looparrowleft" to "↫", "looparrowright" to "↬", "leftrightsquigarrow" to "↭", "rightsquigarrow" to "⇝",
        "leftsquigarrow" to "⇜", "curvearrowleft" to "↶", "curvearrowright" to "↷", "circlearrowleft" to "↺",
        "circlearrowright" to "↻", "twoheadleftarrow" to "↞", "leftleftarrows" to "⇇", "rightrightarrows" to "⇉",
        "upuparrows" to "⇈", "downdownarrows" to "⇊", "leftrightarrows" to "⇆", "rightleftarrows" to "⇄",
        "leftharpoonup" to "↼", "leftharpoondown" to "↽", "rightharpoonup" to "⇀", "rightharpoondown" to "⇁",
        "upharpoonleft" to "↿", "upharpoonright" to "↾", "downharpoonleft" to "⇃", "downharpoonright" to "⇂",
        "leftrightharpoons" to "⇋", "Lleftarrow" to "⇚", "Rrightarrow" to "⇛", "Updownarrow" to "⇕",
        "nleftarrow" to "↚", "nrightarrow" to "↛", "nleftrightarrow" to "↮", "nLeftarrow" to "⇍",
        "nRightarrow" to "⇏", "nLeftrightarrow" to "⇎", "Lsh" to "↰", "Rsh" to "↱", "lightning" to "↯",
        "gets" to "←", "xrightarrow" to "→", "xleftarrow" to "←", "dashrightarrow" to "⇢", "dashleftarrow" to "⇠",
        "multimap" to "⊸", "origof" to "⊶", "imageof" to "⊷", "rightarrowtriangle" to "⇾", "Longmapsto" to "⟾",
        "hookrightarrow" to "↪", "hookleftarrow" to "↩", "mapsto" to "↦", "implies" to "⟹", "iff" to "⟺",
        "varsigma" to "ς", "varbeta" to "ϐ", "varTheta" to "ϴ", "Digamma" to "Ϝ", "koppa" to "ϟ", "sampi" to "ϡ",
        "stigma" to "ϛ", "varUpsilon" to "ϒ", "backslash" to "\\", "textbackslash" to "\\", "setminus" to "∖",
        "enspace" to " ", "thinspace" to " ", "medspace" to " ", "thickspace" to " ",
        "nobreakspace" to " ", "textvisiblespace" to "␣",
        "ldotp" to ".", "cdotp" to "·", "mathunderscore" to "_", "textunderscore" to "_", "textbar" to "|",
        "textless" to "<", "textgreater" to ">", "textasciitilde" to "~", "textasciicircum" to "^",
        "textdollar" to "$", "textpercent" to "%", "textampersand" to "&", "texthash" to "#",
        "lbrace" to "{", "rbrace" to "}", "lbrack" to "[", "rbrack" to "]", "lparen" to "(", "rparen" to ")",
        "llbracket" to "⟦", "rrbracket" to "⟧", "lAngle" to "⟪", "rAngle" to "⟫", "lgroup" to "⟮", "rgroup" to "⟯",
        "ulcorner" to "⌜", "urcorner" to "⌝", "llcorner" to "⌞", "lrcorner" to "⌟", "lmoustache" to "⎰",
        "rmoustache" to "⎱", "lang" to "⟨", "rang" to "⟩", "slash" to "/", "backslashmath" to "∖",
        "overbrace" to "⏞", "underbrace" to "⏟", "overparen" to "⏜", "underparen" to "⏝",
        "hbarsq" to "ℏ²", "degC" to "°C", "degF" to "°F", "ohms" to "Ω", "mum" to "µm",
        "approxeq" to "≊", "propto" to "∝", "varpropto" to "∝", "nabla" to "∇", "grad" to "∇", "curl" to "∇×",
        "divergence" to "∇·", "laplacian" to "∆", "Laplace" to "∆", "braket" to "⟨⟩",
        "S" to "§", "P" to "¶", "section" to "§", "mathsection" to "§", "textsection" to "§", "textparagraph" to "¶",
        "lVert" to "‖", "rVert" to "‖", "circledR" to "®", "circledS" to "Ⓢ", "textcopyright" to "©",
        "smallint" to "∫", "nleqslant" to "≰", "ngeqslant" to "≱", "nleqq" to "≰", "ngeqq" to "≱",
        "varGamma" to "Γ", "varDelta" to "Δ", "varTheta" to "Θ", "varLambda" to "Λ", "varXi" to "Ξ",
        "varPi" to "Π", "varSigma" to "Σ", "varPhi" to "Φ", "varPsi" to "Ψ", "varOmega" to "Ω",
        "triangledown" to "▽", "vartriangle" to "△", "smalltriangleup" to "▵", "smalltriangledown" to "▿",
        "xmark" to "✗", "cross" to "✗", "maltese" to "✠", "diagup" to "╱", "diagdown" to "╲",
        "lessdot" to "⋖", "gtrdot" to "⋗", "eqsim" to "≂", "curlyeqprec" to "⋞", "curlyeqsucc" to "⋟",
        "precnsim" to "⋨", "succnsim" to "⋩", "lnsim" to "⋦", "gnsim" to "⋧", "lnapprox" to "⪉", "gnapprox" to "⪊",
        "wedgeq" to "≙", "veeeq" to "≚", "starequal" to "≛", "measeq" to "≞", "nVdash" to "⊮", "nVDash" to "⊯",
        "VDash" to "⊫", "smallfrown" to "⌢", "smallsmile" to "⌣",
        "textquoteleft" to "‘", "textquoteright" to "’", "textquotedblleft" to "“", "textquotedblright" to "”",
        "textendash" to "–", "textemdash" to "—", "guillemotleft" to "«", "guillemotright" to "»",
        "textasciiacute" to "´", "textdagger" to "†", "textdaggerdbl" to "‡",
        "textellipsis" to "…", "textonehalf" to "½", "textonequarter" to "¼", "textthreequarters" to "¾",
        "textmultiply" to "×", "textdiv" to "÷", "textleftarrow" to "←", "textrightarrow" to "→",
        "textuparrow" to "↑", "textdownarrow" to "↓", "textinfty" to "∞", "textlangle" to "⟨", "textrangle" to "⟩"
    )
}
