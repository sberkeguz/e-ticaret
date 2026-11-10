package com.sarikaya.e_ticaret;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
public class ETicaretApplication {

	public static void main(String[] args) {
		SpringApplication.run(ETicaretApplication.class, args);
	}}