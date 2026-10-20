plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.linecorp.armeria:armeria:1.42.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

application {
    mainClass = "repro.Main"
}
