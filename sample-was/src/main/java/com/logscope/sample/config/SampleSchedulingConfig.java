package com.logscope.sample.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 샘플 WAS의 자동 로그 생성 스케줄을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SampleSchedulingConfig {}
