package com.sarikaya.e_ticaret.payment.service;

import com.iyzipay.Options;
import com.iyzipay.exception.HttpClientException;
import com.iyzipay.model.Address;
import com.iyzipay.model.BasketItem;
import com.iyzipay.model.BasketItemType;
import com.iyzipay.model.Buyer;
import com.iyzipay.model.Currency;
import com.iyzipay.model.Locale;
import com.iyzipay.model.PaymentCard;
import com.iyzipay.model.PaymentChannel;
import com.iyzipay.model.PaymentGroup;
import com.iyzipay.model.PaymentResource;
import com.iyzipay.model.Refund;
import com.iyzipay.model.ThreedsInitialize;
import com.iyzipay.model.ThreedsPayment;
import com.iyzipay.request.CreatePaymentRequest;
import com.iyzipay.request.CreateRefundV2Request;
import com.iyzipay.request.CreateThreedsPaymentRequest;
import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.dto.CheckoutResponse;
import com.sarikaya.e_ticaret.payment.dto.PayOrderRequest;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLHandshakeException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Ödemeler iyzico Ödeme API'si ile alınır. Kart bilgileri sayfadaki formdan gelir,
 * yalnızca iyzico'ya iletilir; veritabanına yazılmaz ve loglanmaz.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PENDING = "PENDING";
    private static final String PAID = "PAID";
    private static final String REVIEW = "REVIEW";
    private static final String CANCELLED = "CANCELLED";
    private static final String REFUNDED = "REFUNDED";
    private static final String ORDER_CANCELLED = "İptal edildi";
    private static final int RESERVATION_MINUTES = 30;

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final ProductRepository products;
    private final UserRepository users;
    private final CartItemRepository cartItems;
    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final String callbackUrl;
    private final String frontendUrl;

    public PaymentService(
            OrderRepository orders,
            PaymentRepository payments,
            ProductRepository products,
            UserRepository users,
            CartItemRepository cartItems,
            @Value("${iyzico.api-key:}") String apiKey,
            @Value("${iyzico.secret-key:}") String secretKey,
            @Value("${iyzico.base-url:https://sandbox-api.iyzipay.com}") String baseUrl,
            @Value("${iyzico.callback-url:http://localhost:8080/api/payments/callback}") String callbackUrl,
            @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.orders = orders;
        this.payments = payments;
        this.products = products;
        this.users = users;
        this.cartItems = cartItems;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = baseUrl;
        this.callbackUrl = callbackUrl;
        this.frontendUrl = frontendUrl;
    }

    /** Sepetten yeni sipariş oluşturur, stoğu ayırır ve kartla ödemeyi başlatır. */
    @Transactional
    public CheckoutResponse initialize(String username, CheckoutRequest request, String ip) {
        requireCredentials();

        User user = users.findByUsernameForUpdate(username)
                .orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı."));

        if (payments.existsByOrder_User_IdAndStatusIn(user.getId(), List.of(PENDING))) {
            throw new BadRequestException("Bu hesap için devam eden bir ödeme var. Siparişlerim sayfasından ödemesini yapın veya iptal edin.");
        }

        Map<Long, Integer> quantitiesByProduct = new TreeMap<>();
        for (var item : request.items()) {
            quantitiesByProduct.merge(item.productId(), item.quantity(), Math::addExact);
        }
        Map<Long, Integer> cartQuantities = new TreeMap<>();
        for (var item : cartItems.findByUser_IdOrderByIdAsc(user.getId())) {
            cartQuantities.put(item.getProduct().getId(), item.getQuantity());
        }
        if (!cartQuantities.equals(quantitiesByProduct)) {
            throw new BadRequestException("Sepetiniz değişti veya bu sipariş zaten tamamlandı. Lütfen sepeti yenileyin.");
        }

        Order order = new Order();
        order.setUser(user);
        order.setFirstName(request.firstName().trim());
        order.setLastName(request.lastName().trim());
        order.setPhone(request.phone().trim());
        order.setCity(request.city().trim());
        order.setDistrict(request.district().trim());
        order.setNeighborhood(request.neighborhood().trim());
        order.setAddress(request.address().trim());
        order.setStatus("Ödeme bekliyor");

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : quantitiesByProduct.entrySet()) {
            Product product = products.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Ürün bulunamadı: " + entry.getKey()));
            int quantity = entry.getValue();
            int stock = product.getStock() == null ? 0 : product.getStock();
            if (!product.isActive()) {
                throw new BadRequestException(product.getName() + " artık satışta değil.");
            }
            if (stock < quantity) {
                throw new BadRequestException(
                        product.getName() + " için yeterli stok yok (kalan: " + stock + ").");
            }

            product.setStock(stock - quantity);
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setPrice(product.getPrice());
            orderItem.setQuantity(quantity);
            order.getItems().add(orderItem);
        }

        if (total.signum() <= 0) {
            throw new BadRequestException("Sipariş tutarı sıfırdan büyük olmalı.");
        }

        order.setTotal(total);
        orders.saveAndFlush(order);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setStatus(PENDING);
        payments.saveAndFlush(payment);

        // Ödeme reddedilirse hata fırlatılır ve sipariş, stok ayırma dahil geri alınır.
        return charge(payment, order, request.email(), request.identityNumber(),
                request.card(), request.forceThreeDS(), ip);
    }

    /** Siparişlerim'den, ödemesi bekleyen siparişi kartla öder. */
    @Transactional
    public CheckoutResponse payExistingOrder(String username, Long orderId, PayOrderRequest request, String ip) {
        requireCredentials();
        Payment payment = ownedPendingPayment(username, orderId);
        return charge(payment, payment.getOrder(), request.email(), request.identityNumber(),
                request.card(), request.forceThreeDS(), ip);
    }

    /** Ödemesi bekleyen siparişi iptal eder ve ayrılan stoğu geri bırakır. */
    @Transactional
    public void cancelPendingOrder(String username, Long orderId) {
        Payment payment = ownedPendingPayment(username, orderId);
        Order order = payment.getOrder();
        releaseStock(order);
        payment.setStatus("CANCELLED");
        order.setStatus("İptal edildi");
    }

    @Transactional
    public void cancelByAdmin(Long orderId, String ip) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));

        if (ORDER_CANCELLED.equals(order.getStatus())) {
            return;
        }

        Optional<Payment> foundPayment = payments.findByOrderIdForUpdate(orderId);

        if (foundPayment.isEmpty()) {
            releaseStock(order);
            order.setStatus(ORDER_CANCELLED);
            return;
        }

        Payment payment = foundPayment.get();
        String paymentStatus = payment.getStatus();
        boolean moneyWasTaken = PAID.equals(paymentStatus) || REVIEW.equals(paymentStatus);
        boolean paymentIsPending = PENDING.equals(paymentStatus);

        if (moneyWasTaken) {
            refund(payment, order, ip);
            payment.setStatus(REFUNDED);
            releaseStock(order);
        } else if (paymentIsPending) {
            payment.setStatus(CANCELLED);
            releaseStock(order);
        }

        order.setStatus(ORDER_CANCELLED);
    }

    private void refund(Payment payment, Order order, String ip) {
        requireCredentials();

        String iyzicoPaymentId = payment.getToken();
        if (iyzicoPaymentId == null) {
            throw new BadRequestException(
                    "iyzico ödeme numarası bulunamadı; iade iyzico panelinden elle yapılmalı.");
        }

        CreateRefundV2Request request = new CreateRefundV2Request();
        request.setLocale(Locale.TR.getValue());
        request.setConversationId(order.getId().toString());
        request.setPaymentId(iyzicoPaymentId);
        request.setPrice(order.getTotal());
        request.setIp(ip);

        Refund result = callProvider(() -> Refund.createV2(request, options()));

        boolean refundSucceeded = "success".equalsIgnoreCase(result.getStatus());
        if (!refundSucceeded) {
            throw new BadRequestException(providerError("İade yapılamadı", result.getErrorMessage()));
        }

        log.info("Sipariş {} için {} TL iade edildi.", order.getId(), order.getTotal());
    }

    private Payment ownedPendingPayment(String username, Long orderId) {
        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));
        Order order = payment.getOrder();
        if (order.getUser() == null || !username.equals(order.getUser().getUsername())) {
            throw new ResourceNotFoundException("Sipariş bulunamadı.");
        }
        if (!PENDING.equals(payment.getStatus())) {
            throw new BadRequestException("Bu siparişin ödemesi artık beklemede değil.");
        }
        return payment;
    }

    private CheckoutResponse charge(Payment payment, Order order, String email, String identityNumber,
                                    CheckoutRequest.Card card, boolean threeDS, String ip) {
        CreatePaymentRequest iyzicoRequest = new CreatePaymentRequest();
        iyzicoRequest.setLocale(Locale.TR.getValue());
        iyzicoRequest.setConversationId(order.getId().toString());
        iyzicoRequest.setPrice(order.getTotal());
        iyzicoRequest.setPaidPrice(order.getTotal());
        iyzicoRequest.setCurrency(Currency.TRY.name());
        iyzicoRequest.setInstallment(1);
        iyzicoRequest.setBasketId(order.getId().toString());
        iyzicoRequest.setPaymentChannel(PaymentChannel.WEB.name());
        iyzicoRequest.setPaymentGroup(PaymentGroup.PRODUCT.name());
        iyzicoRequest.setPaymentCard(paymentCard(card));
        iyzicoRequest.setBuyer(createBuyer(order, email, identityNumber, ip));
        iyzicoRequest.setShippingAddress(createAddress(order));
        iyzicoRequest.setBillingAddress(createAddress(order));
        iyzicoRequest.setBasketItems(basketItems(order));

        if (threeDS) {
            iyzicoRequest.setCallbackUrl(callbackUrl);
            ThreedsInitialize result = callProvider(() -> ThreedsInitialize.create(iyzicoRequest, options()));
            String html = decodeHtml(result.getHtmlContent());
            if (!"success".equalsIgnoreCase(result.getStatus()) || html == null) {
                throw new BadRequestException(providerError("3D Secure doğrulaması başlatılamadı", result.getErrorMessage()));
            }
            payment.setToken(result.getPaymentId());
            payments.save(payment);
            return CheckoutResponse.threeDS(html);
        }

        com.iyzipay.model.Payment result = callProvider(() -> com.iyzipay.model.Payment.create(iyzicoRequest, options()));
        if (!"success".equalsIgnoreCase(result.getStatus())) {
            // Kart reddedildi: para çekilmedi. Hata fırlatınca işlem geri alınır.
            throw new BadRequestException(providerError("Ödeme alınamadı", result.getErrorMessage()));
        }
        payment.setToken(result.getPaymentId());
        boolean valid = result.verifySignature(secretKey) && matchesOrder(order, result);
        applyResult(payment, order, result, valid);
        payments.save(payment);
        return CheckoutResponse.of(resultName(payment));
    }

    /**
     * iyzico, 3D Secure doğrulamasından sonra buraya POST eder.
     * Dönen değer: success | review | failed | declined | pending
     */
    @Transactional
    public String completeThreeDS(String status, String paymentId, String conversationData,
                                  String conversationId, String mdStatus) {
        Long orderId;
        try {
            orderId = Long.valueOf(conversationId);
        } catch (NumberFormatException | NullPointerException e) {
            throw new BadRequestException("Geçersiz ödeme dönüşü.");
        }
        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Ödeme bulunamadı."));
        Order order = payment.getOrder();

        if (!PENDING.equals(payment.getStatus())) {
            return resultName(payment);
        }
        if (payment.getToken() == null || !payment.getToken().equals(paymentId)) {
            throw new BadRequestException("Ödeme dönüşü bu siparişle eşleşmiyor.");
        }

        if (!"success".equalsIgnoreCase(status) || !"1".equals(mdStatus)) {
            // Banka doğrulaması geçmedi; para çekilmedi. Sipariş, yeniden denenebilmesi için beklemede kalır.
            log.info("3D Secure doğrulaması başarısız: sipariş {}, mdStatus {}", orderId, mdStatus);
            return "declined";
        }

        CreateThreedsPaymentRequest request = new CreateThreedsPaymentRequest();
        request.setLocale(Locale.TR.getValue());
        request.setConversationId(order.getId().toString());
        request.setPaymentId(paymentId);
        request.setConversationData(conversationData);
        ThreedsPayment result = callProvider(() -> ThreedsPayment.create(request, options()));

        if (!"success".equalsIgnoreCase(result.getStatus())) {
            log.info("3D Secure ödemesi reddedildi: sipariş {}: {}", orderId, result.getErrorMessage());
            return "declined";
        }

        boolean valid = result.verifySignature(secretKey)
                && paymentId.equals(result.getPaymentId())
                && matchesOrder(order, result);
        applyResult(payment, order, result, valid);
        return resultName(payment);
    }

    @Transactional
    public int expireReservations() {
        List<Payment> expiredPayments = payments
                .findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        PENDING, LocalDateTime.now().minusMinutes(RESERVATION_MINUTES));
        for (Payment payment : expiredPayments) {
            Order order = payment.getOrder();
            releaseStock(order);
            payment.setStatus("EXPIRED");
            order.setStatus("Ödeme süresi doldu");
        }
        return expiredPayments.size();
    }

    private void applyResult(Payment payment, Order order, PaymentResource result, boolean validResponse) {
        // Ödeme API'si (Payment / ThreedsPayment) yanıtında paymentStatus alanı gelmez;
        // status = success ve fraudStatus = 1 ödemenin alındığını gösterir. Alan gelirse
        // SUCCESS dışındaki bir değeri başarısız sayarız.
        boolean paid = "success".equalsIgnoreCase(result.getStatus())
                && Integer.valueOf(1).equals(result.getFraudStatus())
                && (result.getPaymentStatus() == null || "SUCCESS".equalsIgnoreCase(result.getPaymentStatus()));
        boolean review = Integer.valueOf(0).equals(result.getFraudStatus());

        if (!validResponse) {
            // iyzico başarılı dedi ama imza/tutar tutmadı: para çekilmiş olabilir, elle kontrol gerekir.
            log.warn("iyzico yanıtı doğrulanamadı, sipariş incelemeye alındı: {}", order.getId());
            payment.setStatus("REVIEW");
            order.setStatus("Ödeme inceleniyor");
        } else if (paid) {
            payment.setStatus("PAID");
            order.setStatus("Hazırlanıyor");
        } else if (review) {
            payment.setStatus("REVIEW");
            order.setStatus("Ödeme inceleniyor");
        } else {
            releaseStock(order);
            payment.setStatus("FAILED");
            order.setStatus("İptal edildi");
        }

        if ("PAID".equals(payment.getStatus()) || "REVIEW".equals(payment.getStatus())) {
            users.findByIdForUpdate(order.getUser().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı."));
            cartItems.deleteAll(cartItems.findByUser_IdOrderByIdAsc(order.getUser().getId()));
        }
    }

    private boolean matchesOrder(Order order, PaymentResource result) {
        String id = order.getId().toString();
        return id.equals(result.getBasketId())
                && id.equals(result.getConversationId())
                && Currency.TRY.name().equals(result.getCurrency())
                && amountsMatch(order.getTotal(), result.getPrice(), result.getPaidPrice());
    }

    /**
     * iyzico sandbox, yeni başlamış bir JVM'in ilk TLS bağlantılarını zaman zaman el sıkışmada keser
     * ("Remote host terminated the handshake"). El sıkışma tamamlanmadan istek gövdesi gönderilmediği
     * için bu durumda yeniden denemek güvenlidir; çift ödeme oluşmaz.
     */
    private <T> T callProvider(Supplier<T> call) {
        final int attempts = 3;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (HttpClientException failure) {
                if (!hasCause(failure, SSLHandshakeException.class)) {
                    throw failure;
                }
                if (attempt >= attempts) {
                    log.warn("iyzico SSL el sıkışması {} denemede de başarısız", attempts, failure);
                    throw new BadRequestException(
                            "iyzico bağlantısı kurulamadı. Lütfen birkaç saniye sonra tekrar deneyin.");
                }
                log.warn("iyzico SSL el sıkışması başarısız ({}. deneme), tekrar deneniyor: {}", attempt, failure.getMessage());
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new BadRequestException("iyzico bağlantısı kesildi. Lütfen tekrar deneyin.");
                }
            }
        }
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (causeType.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static String decodeHtml(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("<")) {
            return trimmed;
        }
        try {
            return new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String providerError(String prefix, String message) {
        return message == null || message.isBlank() ? prefix + "." : prefix + ": " + message;
    }

    private void requireCredentials() {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            throw new BadRequestException("iyzico API anahtarları yapılandırılmamış.");
        }
    }

    private boolean amountsMatch(BigDecimal expected, BigDecimal price, BigDecimal paidPrice) {
        return price != null && paidPrice != null
                && expected.compareTo(price) == 0
                && expected.compareTo(paidPrice) == 0;
    }

    private static PaymentCard paymentCard(CheckoutRequest.Card card) {
        PaymentCard paymentCard = new PaymentCard();
        paymentCard.setCardHolderName(card.holderName().trim());
        paymentCard.setCardNumber(card.number());
        paymentCard.setExpireMonth(card.expireMonth());
        paymentCard.setExpireYear(card.expireYear());
        paymentCard.setCvc(card.cvc());
        paymentCard.setRegisterCard(0);
        return paymentCard;
    }

    private static List<BasketItem> basketItems(Order order) {
        List<BasketItem> items = new ArrayList<>();
        for (OrderItem orderItem : order.getItems()) {
            BasketItem item = new BasketItem();
            item.setId(orderItem.getProductId().toString());
            item.setName(orderItem.getProductName());
            item.setCategory1("Ürün");
            item.setCategory2("Genel");
            item.setItemType(BasketItemType.PHYSICAL.name());
            item.setPrice(orderItem.getPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity())));
            items.add(item);
        }
        return items;
    }

    private static Buyer createBuyer(Order order, String email, String identityNumber, String ip) {
        Buyer buyer = new Buyer();
        buyer.setId(order.getUser().getId().toString());
        buyer.setName(order.getFirstName());
        buyer.setSurname(order.getLastName());
        buyer.setGsmNumber(order.getPhone());
        buyer.setEmail(email.trim());
        buyer.setIdentityNumber(identityNumber);
        buyer.setRegistrationAddress(order.getAddress());
        buyer.setIp(ip);
        buyer.setCity(order.getCity());
        buyer.setCountry("Turkey");
        return buyer;
    }

    private static Address createAddress(Order order) {
        Address address = new Address();
        address.setContactName(order.getFirstName() + " " + order.getLastName());
        address.setCity(order.getCity());
        address.setCountry("Turkey");
        address.setAddress(order.getAddress());
        return address;
    }

    private void releaseStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Product product = products.findByIdForUpdate(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Ürün bulunamadı: " + item.getProductId()));
            int stock = product.getStock() == null ? 0 : product.getStock();
            product.setStock(stock + item.getQuantity());
        }
    }

    private Options options() {
        Options options = new Options();
        options.setApiKey(apiKey);
        options.setSecretKey(secretKey);
        options.setBaseUrl(baseUrl);
        return options;
    }

    private static String resultName(Payment payment) {
        return switch (payment.getStatus()) {
            case "PAID" -> "success";
            case "REVIEW" -> "review";
            case "FAILED", "EXPIRED", "CANCELLED" -> "failed";
            default -> "pending";
        };
    }
}
