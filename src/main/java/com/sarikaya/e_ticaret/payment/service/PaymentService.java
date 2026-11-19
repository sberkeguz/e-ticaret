package com.sarikaya.e_ticaret.payment.service;

import com.sarikaya.e_ticaret.cart.repository.CartItemRepository;
import com.sarikaya.e_ticaret.exception.BadRequestException;
import com.sarikaya.e_ticaret.exception.PaymentServiceUnavailableException;
import com.sarikaya.e_ticaret.exception.ResourceNotFoundException;
import com.sarikaya.e_ticaret.order.entity.Order;
import com.sarikaya.e_ticaret.order.entity.OrderItem;
import com.sarikaya.e_ticaret.order.entity.OrderStatus;
import com.sarikaya.e_ticaret.order.repository.OrderRepository;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient.AttemptResult;
import com.sarikaya.e_ticaret.payment.client.IyzicoServiceClient.StartPayment;
import com.sarikaya.e_ticaret.payment.dto.CheckoutRequest;
import com.sarikaya.e_ticaret.payment.dto.CheckoutResponse;
import com.sarikaya.e_ticaret.payment.dto.PayOrderRequest;
import com.sarikaya.e_ticaret.payment.dto.PaymentStatusResponse;
import com.sarikaya.e_ticaret.payment.entity.Payment;
import com.sarikaya.e_ticaret.payment.events.PaymentEventTopology;
import com.sarikaya.e_ticaret.payment.outbox.OutboxRelay;
import com.sarikaya.e_ticaret.payment.entity.PaymentStatus;
import com.sarikaya.e_ticaret.payment.repository.PaymentRepository;
import com.sarikaya.e_ticaret.product.entity.Product;
import com.sarikaya.e_ticaret.product.repository.ProductRepository;
import com.sarikaya.e_ticaret.user.entity.User;
import com.sarikaya.e_ticaret.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sipariş, stok ve sepet tarafı. Kartla ödeme IyzicoService'e devredilir:
 * kart bilgisi oraya senkron HTTP ile gider, burada yazılmaz ve loglanmaz.
 *
 * Ödeme sırasında backend kendi ödeme satırını kilitli tutar; böylece aynı siparişe
 * gelen ikinci istek, iptal veya süre dolumu ilk ödeme bitene kadar bekler.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final PaymentStatus PENDING = PaymentStatus.PENDING;
    private static final PaymentStatus PAID = PaymentStatus.PAID;
    private static final PaymentStatus REVIEW = PaymentStatus.REVIEW;
    private static final PaymentStatus CANCELLED = PaymentStatus.CANCELLED;
    private static final PaymentStatus REFUNDED = PaymentStatus.REFUNDED;
    private static final OrderStatus ORDER_CANCELLED = OrderStatus.CANCELLED;
    private static final int RESERVATION_MINUTES = 2;
    /** Para çekme isteğinin iyzico'da sürdüğü ya da sonucunun belirsiz olduğu durumlar. */
    private static final Set<String> IN_FLIGHT_AT_PROVIDER = Set.of("CREATED", "SENT", "CONFIRM_SENT", "VERIFYING");
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final ProductRepository products;
    private final UserRepository users;
    private final CartItemRepository cartItems;
    private final IyzicoServiceClient iyzicoService;
    private final OutboxRelay outbox;

    /** Eski yerel (simüle) 2D ödemelerin numara öneki: iadesi iyzico'ya gönderilmez. */
    static final String LOCAL_PAYMENT_PREFIX = "LOCAL-";
    /** Kuyruğa giden 2D isteği bu süre içinde iyzico'ya gönderilmezse geçersiz olur (geç çekim olmasın). */
    static final Duration CHARGE_WINDOW = Duration.ofSeconds(50);
    /**
     * Kuyruktaki isteğin sonucu bu kadar gecikirse uyarı logu yazılır (RabbitMQ veya iyzico-service
     * çalışmıyor demektir). Sipariş yine de sonuç gelene kadar beklemede kalır.
     */
    static final Duration STALE_REQUEST_WARNING = Duration.ofMinutes(10);
    /** Kuyrukla giden 2D isteğinin backend'deki durumu; sonuç olayla gelene kadar böyle kalır. */
    static final String REQUESTED = "REQUESTED";

    public PaymentService(
            OrderRepository orders,
            PaymentRepository payments,
            ProductRepository products,
            UserRepository users,
            CartItemRepository cartItems,
            IyzicoServiceClient iyzicoService,
            OutboxRelay outbox) {
        this.outbox = outbox;
        this.orders = orders;
        this.payments = payments;
        this.products = products;
        this.users = users;
        this.cartItems = cartItems;
        this.iyzicoService = iyzicoService;
    }

    /** Sepetten yeni sipariş oluşturur, stoğu ayırır ve kartla ödemeyi başlatır. */
    @Transactional
    public CheckoutResponse initialize(String username, CheckoutRequest request, String ip, String idempotencyKey) {
        requireValidKey(idempotencyKey);
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
        order.setStatus(OrderStatus.PAYMENT_PENDING);

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
            // Ürün başka sepetlerde kalan stoktan fazla duruyorsa oradan çıkar/azalt.
            // Ödeme reddedilirse bu da işlemle birlikte geri alınır.
            cartItems.fitToStock(product.getId(), product.getStock(), user.getId());
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
        return charge(payment, order, user, request.email(), request.identityNumber(),
                request.card(), request.forceThreeDS(), ip, idempotencyKey);
    }

    /** Siparişlerim'den, ödemesi bekleyen siparişi kartla öder. */
    @Transactional
    public CheckoutResponse payExistingOrder(String username, Long orderId, PayOrderRequest request,
                                             String ip, String idempotencyKey) {
        requireValidKey(idempotencyKey);
        Payment payment = ownedPendingPayment(username, orderId);
        Order order = payment.getOrder();
        return charge(payment, order, order.getUser(), request.email(), request.identityNumber(),
                request.card(), request.forceThreeDS(), ip, idempotencyKey);
    }

    /** Ödemesi bekleyen siparişi iptal eder ve ayrılan stoğu geri bırakır. */
    @Transactional
    public void cancelPendingOrder(String username, Long orderId) {
        Payment payment = ownedPendingPayment(username, orderId);
        requireNoChargeInFlight(payment);
        Order order = payment.getOrder();
        releaseStock(order);
        payment.setStatus(CANCELLED);
        order.setStatus(ORDER_CANCELLED);
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
        PaymentStatus paymentStatus = payment.getStatus();
        boolean moneyWasTaken = PAID.equals(paymentStatus) || REVIEW.equals(paymentStatus);
        boolean paymentIsPending = PENDING.equals(paymentStatus);

        if (moneyWasTaken) {
            refund(payment, order, ip);
            payment.setStatus(REFUNDED);
            releaseStock(order);
        } else if (paymentIsPending) {
            requireNoChargeInFlight(payment);
            payment.setStatus(CANCELLED);
            releaseStock(order);
        }

        order.setStatus(ORDER_CANCELLED);
    }

    private void refund(Payment payment, Order order, String ip) {
        if (payment.getToken() != null && payment.getToken().startsWith(LOCAL_PAYMENT_PREFIX)) {
            // Eski yerel (simüle) 2D ödeme: para çekilmemişti, iyzico'ya iade isteği gönderilmez.
            log.info("Sipariş {} yerel 2D ödemeydi; iade iyzico'ya gönderilmeden kaydedildi.", order.getId());
            return;
        }
        if (payment.getAttemptId() != null) {
            iyzicoService.refund(payment.getAttemptId(), ip);
        } else if (payment.getToken() != null) {
            // IyzicoService'ten önce alınmış ödeme: deneme kaydı yok, iyzico ödeme numarasıyla iade edilir.
            iyzicoService.refundLegacy(payment.getToken(), order.getTotal(), ip, order.getId());
        } else {
            throw new BadRequestException(
                    "iyzico ödeme numarası bulunamadı; iade iyzico panelinden elle yapılmalı.");
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

    private CheckoutResponse charge(Payment payment, Order order, User user, String email, String identityNumber,
                                    CheckoutRequest.Card card, boolean threeDS, String ip, String clientKey) {
        // Anahtar kullanıcıya özel olsun: başka bir hesabın anahtarıyla çakışamaz.
        String idempotencyKey = "u" + user.getId() + "-" + clientKey;
        if (!threeDS && card != null) {
            return requestTwoDCharge(payment, order, startPayment(order, user, email, identityNumber, card, false, ip),
                    idempotencyKey);
        }
        // Kart yoksa iyzico Ödeme Formu: kart bilgisi bize hiç gelmez, müşteri iyzico'nun formunu doldurur.
        boolean checkoutForm = card == null;
        // 2D: servis iyzico'yu beklemeden kabul eder (asenkron). 3D ve form: içerik hemen gerekir (senkron).
        boolean async = !threeDS && !checkoutForm;
        AttemptResult result = iyzicoService.startPayment(idempotencyKey,
                startPayment(order, user, email, identityNumber, card, threeDS, ip), async);

        payment.setAttemptId(result.attemptId());
        if (result.iyzicoPaymentId() != null) {
            payment.setToken(result.iyzicoPaymentId());
        }
        return switch (result.status()) {
            // Kabul edildi, iyzico arka planda çağrılıyor. Sipariş "Ödeme bekliyor"da kalır;
            // sonuç RabbitMQ olayıyla gelir, tarayıcı da durum adresini sorar.
            case "CREATED", "SENT" -> {
                payments.save(payment);
                yield CheckoutResponse.processing(order.getId());
            }
            case "AWAITING_3DS" -> {
                payments.save(payment);
                yield checkoutForm
                        ? CheckoutResponse.form(order.getId(), result.checkoutFormContent())
                        : CheckoutResponse.threeDS(result.threeDSHtmlContent());
            }
            case "SUCCEEDED", "VERIFYING" -> {
                applyAttemptResult(payment, order, result.status());
                payments.save(payment);
                yield CheckoutResponse.of(resultName(payment));
            }
            // Para çekilmedi. Hata fırlatınca işlem geri alınır.
            case "FAILED" -> throw new BadRequestException(checkoutForm
                    ? withReason("Ödeme formu açılamadı", result.failureReason())
                    : threeDS
                    ? withReason("3D Secure doğrulaması başlatılamadı", result.failureReason())
                    : withReason("Ödeme alınamadı", result.failureReason()));
            default -> throw new BadRequestException(
                    "Ödeme işleniyor. Birkaç saniye sonra Siparişlerim sayfasını kontrol edin.");
        };
    }

    /**
     * 2D ödeme kuyrukla yapılır: istek (komut) siparişle aynı işlemde outbox'a yazılır ve RabbitMQ ile
     * iyzico-service'e gider. Backend iyzico-service'e HTTP isteği atmaz; servis kapalıyken de sipariş
     * alınır ve istek kuyrukta bekler. Sonuç RabbitMQ olayıyla gelir; ekran sonucu GET .../status ile sorar.
     *
     * İstek CHARGE_WINDOW içinde işlenmezse iyzico-service onu iyzico'ya göndermeden reddeder; böylece
     * süresi dolmuş bir siparişten sonradan para çekilmez.
     */
    private CheckoutResponse requestTwoDCharge(Payment payment, Order order, StartPayment request, String idempotencyKey) {
        if (REQUESTED.equals(payment.getLastAttemptStatus())) {
            // Önceki isteğin sonucu henüz gelmedi; para çekilmiş olabilir. İkinci istek gönderilmez.
            throw new BadRequestException("Ödemeniz şu an işleniyor. Sonuçlandıktan sonra tekrar deneyin.");
        }
        UUID attemptId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(CHARGE_WINDOW);
        payment.setAttemptId(attemptId);
        payment.setLastAttemptStatus(REQUESTED);
        payment.setFailureReason(null);
        payment.setChargeDeadline(deadline);
        payments.save(payment);
        outbox.enqueue(PaymentEventTopology.EXCHANGE, PaymentEventTopology.CHARGE_ROUTING_KEY, ChargeRequested.MESSAGE_TYPE,
                new ChargeRequested(ChargeRequested.MESSAGE_TYPE, 1, attemptId, idempotencyKey, deadline, request));
        log.info("Sipariş {} için 2D ödeme isteği kuyruğa yazıldı (deneme {})", order.getId(), attemptId);
        return CheckoutResponse.processing(order.getId());
    }

    /** iyzico-service'e giden 2D ödeme komutu. request, servisin POST /payments gövdesiyle aynıdır. */
    record ChargeRequested(String type, int version, UUID attemptId, String idempotencyKey, Instant deadline,
                           StartPayment request) {
        static final String MESSAGE_TYPE = "ChargeRequested";
    }

    /**
     * iyzico, 3D Secure doğrulamasından sonra tarayıcıyı buraya POST ettirir; biz IyzicoService'e iletiriz.
     * conversationId = ödeme denemesi numarası. Dönen değer: success | review | failed | declined | pending
     */
    @Transactional
    public String completeThreeDS(IyzicoServiceClient.ThreeDSCallback callback) {
        UUID attemptId;
        try {
            attemptId = UUID.fromString(callback.conversationId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Geçersiz ödeme dönüşü.");
        }
        Payment payment = payments.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Ödeme bulunamadı."));

        // Sipariş iptal edildiyse veya süresi dolduysa dönüş iletilmez; para çekilmez.
        if (!PENDING.equals(payment.getStatus())) {
            return resultName(payment);
        }

        AttemptResult result = iyzicoService.completeThreeDS(attemptId, callback);
        return switch (result.status()) {
            case "SUCCEEDED", "VERIFYING" -> {
                applyAttemptResult(payment, payment.getOrder(), result.status());
                yield resultName(payment);
            }
            // Banka doğrulaması geçmedi veya kart reddedildi; para çekilmedi.
            // Sipariş, yeniden denenebilmesi için beklemede kalır.
            case "FAILED" -> "declined";
            default -> "pending";
        };
    }

    /**
     * iyzico Ödeme Formu tamamlanınca tarayıcı buraya POST edilir (adreste deneme numarası, gövdede
     * formun token'ı). Sonucu IyzicoService iyzico'ya sorar. Dönen değer: success | review | declined | pending
     */
    @Transactional
    public String completeCheckoutForm(String attempt, String token) {
        UUID attemptId;
        try {
            attemptId = UUID.fromString(attempt);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Geçersiz ödeme dönüşü.");
        }
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Geçersiz ödeme dönüşü.");
        }
        Payment payment = payments.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Ödeme bulunamadı."));
        if (!PENDING.equals(payment.getStatus())) {
            return resultName(payment);
        }
        AttemptResult result = iyzicoService.completeCheckoutForm(attemptId, token);
        if (result.iyzicoPaymentId() != null) {
            payment.setToken(result.iyzicoPaymentId());
        }
        return switch (result.status()) {
            case "SUCCEEDED", "VERIFYING" -> {
                applyAttemptResult(payment, payment.getOrder(), result.status());
                yield resultName(payment);
            }
            // Kart reddedildi; para çekilmedi. Sipariş, yeniden denenebilmesi için beklemede kalır.
            case "FAILED" -> "declined";
            default -> "pending";
        };
    }

    /**
     * Tarayıcının "ödemem ne oldu?" sorusu (asenkron 2D'de "Ödemeniz işleniyor…" ekranı bunu sorar).
     * Sonuç genelde RabbitMQ olayıyla çoktan siparişe işlenmiştir. Olay gecikirse burada
     * IyzicoService'e doğrudan sorulur ve aynı kuralla siparişe işlenir.
     */
    @Transactional
    public PaymentStatusResponse paymentStatus(String username, Long orderId) {
        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sipariş bulunamadı."));
        Order order = payment.getOrder();
        if (order.getUser() == null || !username.equals(order.getUser().getUsername())) {
            throw new ResourceNotFoundException("Sipariş bulunamadı.");
        }
        if (!PENDING.equals(payment.getStatus()) || payment.getAttemptId() == null) {
            return PaymentStatusResponse.of(statusName(payment));
        }
        if (payment.getLastAttemptStatus() != null) {
            // Kuyrukla giden 2D: sonuç olayla gelir; iyzico-service'e sorulmaz.
            return "FAILED".equals(currentAttemptStatus(payment))
                    ? new PaymentStatusResponse("declined", payment.getFailureReason())
                    : PaymentStatusResponse.of("processing");
        }
        AttemptResult attempt;
        try {
            attempt = iyzicoService.getAttempt(payment.getAttemptId());
        } catch (RuntimeException unavailable) {
            return PaymentStatusResponse.of("processing");
        }
        return switch (attempt.status()) {
            case "SUCCEEDED", "VERIFYING" -> {
                applyAttemptResult(payment, order, attempt.status());
                yield PaymentStatusResponse.of(statusName(payment));
            }
            // Para çekilmedi; sipariş beklemede kalır, kullanıcı başka kartla tekrar deneyebilir.
            case "FAILED" -> new PaymentStatusResponse("declined", attempt.failureReason());
            case "AWAITING_3DS" -> PaymentStatusResponse.of("pending");
            default -> PaymentStatusResponse.of("processing");
        };
    }

    /**
     * Süresi dolan rezervasyonları bırakır. Ödemesi o anda iyzico'da işlenen sipariş
     * (CREATED/SENT/CONFIRM_SENT/VERIFYING) atlanır: sonucu beklenir, yoksa para çekilip sipariş
     * düşmüş olabilirdi. IyzicoService'e ulaşılamazsa da atlanır (güvenli taraf).
     */
    @Transactional
    public int expireReservations() {
        List<Payment> candidates = payments
                .findTop100ByStatusAndReservationStartedAtBeforeOrderByReservationStartedAtAsc(
                        PENDING, Instant.now().minus(Duration.ofMinutes(RESERVATION_MINUTES)));
        int expired = 0;
        for (Payment payment : candidates) {
            Order order = payment.getOrder();
            String attemptStatus = currentAttemptStatus(payment);
            // AWAITING_3DS: müşteri iyzico formunda ya da bankanın SMS sayfasında; o sırada sipariş düşerse
            // ödeme yine de alınabilir (geç ödeme). Bu denemeyi iyzico-service sonuca bağlar (3D: 15 dk,
            // form: 35 dk); başarısız olunca sipariş sonraki turda düşer.
            if (attemptStatus == null || IN_FLIGHT_AT_PROVIDER.contains(attemptStatus) || "AWAITING_3DS".equals(attemptStatus)) {
                log.info("Sipariş {} ödemesi işleniyor ({}), süre dolumu ertelendi", order.getId(), attemptStatus);
                warnIfResultIsLate(payment);
                continue;
            }
            if ("SUCCEEDED".equals(attemptStatus)) {
                // Olay henüz gelmedi ama para alınmış: siparişi düşürme, tamamla.
                applyAttemptResult(payment, order, attemptStatus);
                continue;
            }
            releaseStock(order);
            payment.setStatus(PaymentStatus.EXPIRED);
            order.setStatus(OrderStatus.PAYMENT_EXPIRED);
            expired++;
        }
        return expired;
    }

    private void warnIfResultIsLate(Payment payment) {
        if (REQUESTED.equals(payment.getLastAttemptStatus())
                && Instant.now().isAfter(payment.getChargeDeadline().plus(STALE_REQUEST_WARNING))) {
            log.warn("Sipariş {} 2D ödeme isteğinin sonucu {} dakikadır gelmedi; RabbitMQ ve iyzico-service kontrol edilmeli. "
                            + "Sipariş sonuç gelene kadar beklemede tutuluyor.",
                    payment.getOrder().getId(), Duration.between(payment.getChargeDeadline(), Instant.now()).toMinutes());
        }
    }

    /**
     * Para çekme isteği o anda iyzico'da sürüyorsa sipariş iptal edilmez: iptal edilseydi para
     * çekilip hemen iade edilecekti (kullanıcının kartında çekim + iade görünürdü).
     */
    private void requireNoChargeInFlight(Payment payment) {
        String attemptStatus = currentAttemptStatus(payment);
        if (attemptStatus == null) {
            throw new PaymentServiceUnavailableException();
        }
        if (IN_FLIGHT_AT_PROVIDER.contains(attemptStatus)) {
            throw new BadRequestException("Ödemeniz şu an işleniyor. Sonuçlandıktan sonra iptal edebilirsiniz.");
        }
    }

    /**
     * Denemesi yoksa "NONE"; servise ulaşılamazsa null. Kuyrukla giden 2D'de iyzico-service'e sorulmaz,
     * backend'in olaylardan bildiği durum döner. Sonuç olayı gelmediyse istek işleniyor sayılır ("SENT"):
     * süre dolmuş olsa bile tahmin yürütülmez. iyzico-service her istek için mutlaka bir sonuç yayınlar
     * (süresi geçen isteği kendisi reddeder) ve outbox'ı sayesinde bu sonuç kaybolmaz; RabbitMQ veya servis
     * bir süre çalışmasa da sonuç sonunda gelir. Böylece para çekilmişken sipariş düşürülmez.
     */
    private String currentAttemptStatus(Payment payment) {
        if (payment.getAttemptId() == null) {
            return "NONE";
        }
        if (payment.getLastAttemptStatus() != null) {
            if (!REQUESTED.equals(payment.getLastAttemptStatus())) {
                return payment.getLastAttemptStatus();
            }
            return "SENT";
        }
        try {
            return iyzicoService.getAttempt(payment.getAttemptId()).status();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** IyzicoService sonucunu sipariş durumuna yansıtır. SUCCEEDED → PAID, VERIFYING → REVIEW. */
    public void applyAttemptResult(Payment payment, Order order, String attemptStatus) {
        if (payment.getLastAttemptStatus() != null) {
            payment.setLastAttemptStatus(attemptStatus);
        }
        if ("SUCCEEDED".equals(attemptStatus)) {
            payment.setStatus(PAID);
            order.setStatus(OrderStatus.PREPARING);
        } else {
            // Para çekilmiş olabilir ama doğrulanamadı: elle veya mutabakatla kontrol edilir.
            log.warn("Sipariş {} ödemesi doğrulanamadı, incelemeye alındı", order.getId());
            payment.setStatus(REVIEW);
            order.setStatus(OrderStatus.PAYMENT_REVIEW);
        }
        users.findByIdForUpdate(order.getUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı."));
        cartItems.deleteAll(cartItems.findByUser_IdOrderByIdAsc(order.getUser().getId()));
    }

    private static StartPayment startPayment(Order order, User user, String email, String identityNumber,
                                             CheckoutRequest.Card card, boolean threeDS, String ip) {
        String contactName = order.getFirstName() + " " + order.getLastName();
        List<IyzicoServiceClient.BasketItem> items = order.getItems().stream()
                .map(item -> new IyzicoServiceClient.BasketItem(
                        item.getProductId().toString(),
                        item.getProductName(),
                        item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
        return new StartPayment(
                order.getId(), order.getTotal(), threeDS, ip,
                new IyzicoServiceClient.Buyer(user.getId().toString(), order.getFirstName(), order.getLastName(),
                        email.trim(), order.getPhone(), identityNumber, order.getCity(), order.getAddress()),
                new IyzicoServiceClient.Address(contactName, order.getCity(), order.getAddress()),
                items,
                card == null ? null : new IyzicoServiceClient.Card(card.holderName().trim(), card.number(),
                        card.expireMonth(), card.expireYear(), card.cvc()),
                card == null ? "checkout-form" : "direct");
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

    private static void requireValidKey(String idempotencyKey) {
        if (idempotencyKey == null || !CLIENT_KEY.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Geçersiz Idempotency-Key. Sayfayı yenileyip tekrar deneyin.");
        }
    }

    private static String withReason(String prefix, String reason) {
        return reason == null || reason.isBlank() ? prefix + "." : prefix + ": " + reason;
    }

    private static String statusName(Payment payment) {
        return switch (payment.getStatus()) {
            case PAID -> "success";
            case REVIEW -> "review";
            case PENDING -> "pending";
            default -> "failed";
        };
    }

    private static String resultName(Payment payment) {
        return switch (payment.getStatus()) {
            case PAID -> "success";
            case REVIEW -> "review";
            case FAILED, EXPIRED, CANCELLED -> "failed";
            default -> "pending";
        };
    }
}
