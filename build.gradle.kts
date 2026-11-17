plugins {
    java
}

group = "de.auktoriator"
version = "0.1.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.253-stable")
    testImplementation("com.github.retrooper:packetevents-spigot:2.14.0")
    // Zur Laufzeit bringt Paper Netty mit, PacketEvents gibt es nur zum Kompilieren weiter.
    testRuntimeOnly("io.netty:netty-buffer:4.1.72.Final")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    // Kein Toolchain-Download: lokal laeuft ein neueres JDK, Ziel ist Java 25 (Paper 26.x).
    compileJava {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
    }
    jar {
        archiveFileName.set("TextGuard-${project.version}.jar")
    }
}
