plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example.payments2"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	implementation("org.bouncycastle:bcprov-jdk18on:1.86")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val webInstall = tasks.register<Exec>("webInstall") {
	workingDir("web")
	inputs.files("web/package.json", "web/package-lock.json")
	outputs.dir("web/node_modules")
	if (System.getProperty("os.name").startsWith("Windows")) {
		commandLine("cmd", "/c", "npm.cmd", "ci", "--no-fund", "--no-audit")
	} else commandLine("npm", "ci", "--no-fund", "--no-audit")
}

val webBuild = tasks.register<Exec>("webBuild") {
	dependsOn(webInstall)
	workingDir("web")
	inputs.files(fileTree("web/src"), "web/index.html", "web/tsconfig.json", "web/vite.config.ts", "web/package-lock.json")
	outputs.dir("web/dist")
	if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", "npm.cmd", "run", "build")
	else commandLine("npm", "run", "build")
}

val webTest = tasks.register<Exec>("webTest") {
	group = "verification"
	description = "Runs the web panel unit tests."
	dependsOn(webInstall)
	workingDir("web")
	if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", "npm.cmd", "test")
	else commandLine("npm", "test")
}

tasks.check {
	dependsOn(webTest)
}

tasks.processResources {
	if (!providers.gradleProperty("skipWebBuild").isPresent) dependsOn(webBuild)
	from("web/dist") { into("static") }
	doFirst { require(file("web/dist/index.html").isFile) { "Build web/dist before using -PskipWebBuild." } }
}

tasks.bootJar {
	archiveFileName = "payment-core.jar"
}

tasks.withType<Test> {
	useJUnitPlatform()
	dependsOn(tasks.bootJar)
	systemProperty("payment.bootstrap.jar", tasks.bootJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.register<JavaExec>("browserTestServer") {
	group = "verification"
	description = "Starts an isolated PostgreSQL-backed server for browser tests on port 18080."
	dependsOn(tasks.testClasses)
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass = "com.example.payments2.payment_core2.WebScenarioServer"
	javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}
