package com.exp.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.cdimascio.dotenv.Dotenv;

//mvn spring-boot:run
//mvn clean install


@SpringBootApplication
@EnableScheduling
public class ServerApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.load();
        System.setProperty("MONGODB_URI", dotenv.get("MONGODB_URI"));

		SpringApplication.run(ServerApplication.class, args);
	}

}
