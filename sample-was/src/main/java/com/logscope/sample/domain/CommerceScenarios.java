package com.logscope.sample.domain;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 실제 비즈니스 작업을 하지 않는 이커머스 합성 관측 상황이다. */
public final class CommerceScenarios {
  public static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

  public record Scenario(String code, String category, String label, String level, String detail) {
    /** 합성 표시·분야·오류 코드·상황 설명을 하나의 메시지로 만든다. */
    public String message() {
      return "[합성][" + category + "][" + code + "] " + detail;
    }

    /** 선택한 ERROR 상황의 합성 예외를 만들며 외부 시스템 장애를 유발하지 않는다. */
    public RuntimeException failure() {
      if (!level.equals("ERROR")) throw new IllegalArgumentException("Scenario is not an error");
      return switch (category) {
        case "주문" -> new OrderProcessingException(detail);
        case "결제" ->
            new PaymentProcessingException(
                detail,
                code.equals("PAYMENT_TIMEOUT")
                    ? new java.net.SocketTimeoutException("합성 결제 승인 응답 대기 3000ms 초과")
                    : null);
        case "재고" -> new InventoryConsistencyException(detail);
        default -> new FulfillmentException(detail);
      };
    }
  }

  public static final List<Scenario> ALL =
      List.of(
          new Scenario(
              "INVENTORY_CHECK_STARTED",
              "재고",
              "옵션별 재고 조회 시작",
              "TRACE",
              "옵션별 재고 조회 시작: sku=synthetic-shirt-black-m, warehouse=synthetic-warehouse-a"),
          new Scenario(
              "PAYMENT_ROUTE_SELECTED",
              "결제",
              "결제 승인 경로 선택",
              "TRACE",
              "결제 승인 경로 선택: orderId=synthetic-order-001, provider=synthetic-pg, timeoutMs=3000"),
          new Scenario(
              "ORDER_TOTAL_CALCULATED",
              "주문",
              "주문 금액 계산",
              "DEBUG",
              "주문 금액 계산 완료: orderId=synthetic-order-001, 상품금액=59000, 할인금액=5000, 결제예정금액=54000, currency=KRW"),
          new Scenario(
              "INVENTORY_RESERVATION_CHECKED",
              "재고",
              "재고 예약 가능 수량 확인",
              "DEBUG",
              "재고 예약 가능 수량 확인: sku=synthetic-shirt-black-m, 실재고=8, 예약수량=3, 판매가능수량=5, 요청수량=1"),
          new Scenario(
              "ORDER_ACCEPTED",
              "주문",
              "주문 접수 완료",
              "INFO",
              "주문 접수 완료: orderId=synthetic-order-001, sku=synthetic-shirt-black-m, 수량=1, 상태=PAYMENT_PENDING"),
          new Scenario(
              "PAYMENT_APPROVED",
              "결제",
              "결제 승인 완료",
              "INFO",
              "결제 승인 완료: orderId=synthetic-order-001, 결제금액=54000, currency=KRW, elapsedMs=180"),
          new Scenario(
              "INVENTORY_RESERVED",
              "재고",
              "옵션 재고 예약 완료",
              "INFO",
              "옵션 재고 예약 완료: orderId=synthetic-order-001, sku=synthetic-shirt-black-m, 예약수량=1, 판매가능수량=4"),
          new Scenario(
              "ORDER_DUPLICATE_BLOCKED",
              "주문",
              "중복 주문 요청 차단",
              "WARN",
              "중복 주문 요청 차단: orderId=synthetic-order-001, 기존 주문 상태=PAYMENT_PENDING, 추가 주문 생성 생략"),
          new Scenario(
              "ORDER_PRICE_CHANGED",
              "주문",
              "주문 시점 상품 가격 변경",
              "WARN",
              "주문 요청 거절: sku=synthetic-shirt-black-m, 장바구니금액=49000, 현재금액=54000, 가격 재확인 필요"),
          new Scenario(
              "PAYMENT_DECLINED",
              "결제",
              "결제 승인 거절",
              "WARN",
              "결제 승인 거절: orderId=synthetic-order-001, providerCode=SYNTHETIC_LIMIT_EXCEEDED, 재고 예약 해제 요청"),
          new Scenario(
              "INVENTORY_INSUFFICIENT",
              "재고",
              "옵션 재고 부족",
              "WARN",
              "재고 부족으로 주문 요청 거절: sku=synthetic-shirt-black-m, 실재고=3, 예약수량=2, 판매가능수량=1, 요청수량=2"),
          new Scenario(
              "FULFILLMENT_DELAYED",
              "배송",
              "출고 처리 지연",
              "WARN",
              "출고 처리 지연: orderId=synthetic-order-001, warehouse=synthetic-warehouse-a, 대기시간분=45, 기준분=30"),
          new Scenario(
              "ORDER_SAVE_FAILED",
              "주문",
              "주문 저장 실패",
              "ERROR",
              "주문 오류: 주문 저장 트랜잭션 실패, orderId=synthetic-order-001, 원인=잠금 대기 시간 초과, 결과=주문 저장 롤백"),
          new Scenario(
              "ORDER_PAYMENT_STATE_MISMATCH",
              "주문",
              "주문과 결제 상태 불일치",
              "ERROR",
              "주문 오류: 결제 승인 후 주문 상태 갱신 실패, orderId=synthetic-order-001, 주문상태=PAYMENT_PENDING, 결제상태=APPROVED, 대사 필요"),
          new Scenario(
              "PAYMENT_TIMEOUT",
              "결제",
              "결제 승인 응답 시간 초과",
              "ERROR",
              "결제 오류: 승인 응답 시간 초과, orderId=synthetic-order-001, provider=synthetic-pg, timeoutMs=3000, 승인 여부 미확정, 중복 결제 방지를 위해 승인 조회 필요"),
          new Scenario(
              "PAYMENT_AMOUNT_MISMATCH",
              "결제",
              "결제 승인 금액 불일치",
              "ERROR",
              "결제 오류: 승인 금액 불일치, orderId=synthetic-order-001, 주문금액=54000, 승인금액=59000, currency=KRW, 주문 확정 보류"),
          new Scenario(
              "PAYMENT_REFUND_FAILED",
              "결제",
              "결제 취소 환불 실패",
              "ERROR",
              "결제 오류: 취소 주문 환불 요청 실패, orderId=synthetic-order-001, 환불금액=54000, providerCode=SYNTHETIC_TEMPORARY_UNAVAILABLE, 환불 대기 유지"),
          new Scenario(
              "INVENTORY_STOCK_MISMATCH",
              "재고",
              "판매 가능 재고와 실재고 불일치",
              "ERROR",
              "실재고 오류: 전산 재고와 창고 실사 수량 불일치, sku=synthetic-shirt-black-m, warehouse=synthetic-warehouse-a, 전산재고=5, 실사재고=2, 차이=3, 판매 보류 및 재고 대사 필요"),
          new Scenario(
              "INVENTORY_VERSION_CONFLICT",
              "재고",
              "동시 재고 차감 충돌",
              "ERROR",
              "실재고 오류: 동시 주문 재고 차감 충돌, orderId=synthetic-order-001, sku=synthetic-shirt-black-m, expectedVersion=12, actualVersion=13, 재시도횟수=3, 재고 확정 실패"),
          new Scenario(
              "FULFILLMENT_REQUEST_FAILED",
              "배송",
              "창고 출고 요청 실패",
              "ERROR",
              "배송 오류: 창고 출고 요청 실패, orderId=synthetic-order-001, warehouse=synthetic-warehouse-a, 응답코드=503, 주문 상태=FULFILLMENT_PENDING 유지"));

  /** 지정 코드와 레벨의 일치를 검사하거나 해당 레벨에서 랜덤 상황을 고른다. */
  public static Scenario select(String level, String code) {
    if (!LEVELS.contains(level)) throw new IllegalArgumentException("Invalid level");
    if (code != null) {
      var selected =
          ALL.stream()
              .filter(s -> s.code().equals(code))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown scenario"));
      if (!selected.level().equals(level))
        throw new IllegalArgumentException("Scenario level mismatch");
      return selected;
    }
    var candidates = ALL.stream().filter(s -> s.level().equals(level)).toList();
    return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
  }

  static class OrderProcessingException extends RuntimeException {
    /** 합성 주문 처리 실패의 설명을 보관한다. */
    OrderProcessingException(String message) {
      super(message);
    }
  }

  static class PaymentProcessingException extends RuntimeException {
    /** 합성 결제 실패의 설명과 하위 원인 체인을 보관한다. */
    PaymentProcessingException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static class InventoryConsistencyException extends RuntimeException {
    /** 합성 재고 정합성 실패의 설명을 보관한다. */
    InventoryConsistencyException(String message) {
      super(message);
    }
  }

  static class FulfillmentException extends RuntimeException {
    /** 합성 출고 처리 실패의 설명을 보관한다. */
    FulfillmentException(String message) {
      super(message);
    }
  }
}
