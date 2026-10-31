repositories {
    // The CloudStream app classes (including the Plugin base class used for the
    // settings button) are not shipped in the library AAR. The official
    // pre-release publishes them as classes.jar; pull it as compileOnly so the
    // app classes are never packaged inside the .cs3.
    ivy {
        url = uri("https://github.com/recloudstream/cloudstream/releases/download/pre-release")
        patternLayout { artifact("[artifact].[ext]") }
        metadataSources { artifact() }
        content { includeModule("cloudstream", "classes") }
    }
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 4

dependencies {
    compileOnly("cloudstream:classes:pre-release@jar")
}

cloudstream {
    description = "Catalogo KissKH nativo, drama e film asiatici con sottotitoli ITA/ENG e traduzione DeepL opzionale."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("AsianDrama", "TvSeries", "Movie", "Anime", "AnimeMovie")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/KissKH.png"
}
