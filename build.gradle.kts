plugins {
	java
	id("org.springframework.boot") version "3.5.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.munashe04"
version = "0.0.1-SNAPSHOT"
description = "WhatsApp Bot for Buyza"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation ("com.fasterxml.jackson.core:jackson-databind")
	implementation ("commons-codec:commons-codec:1.16.0")
	implementation ("com.google.apis:google-api-services-sheets:v4-rev20240402-2.0.0")

	//implementation ("com.google.api-client:google-api-client:2.7.0")
	implementation ("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
	implementation("com.google.apis:google-api-services-sheets:v4-rev608-1.25.0")
	implementation ("com.google.http-client:google-http-client-jackson2:1.43.3")

	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
