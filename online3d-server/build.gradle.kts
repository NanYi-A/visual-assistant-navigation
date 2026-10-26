plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("cn.visualassistant.server.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }
