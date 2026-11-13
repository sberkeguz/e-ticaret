// Backend API'sinin döndürdüğü verinin tipleri (Spring tarafındaki DTO'larla birebir).
// Kurallar: Java Long/int/BigDecimal → number (Jackson sayı olarak yazar), Instant → ISO metni
// ("2026-11-13T10:00:00Z"), enum → kod metni. Backend'de bir DTO değişirse buradaki tip de değişmeli.

export type IsoInstant = string;

export type Role = "USER" | "ADMIN";

export type OrderStatus =
    | "PAYMENT_PENDING"
    | "PAYMENT_REVIEW"
    | "PREPARING"
    | "SHIPPED"
    | "DELIVERED"
    | "CANCELLED"
    | "PAYMENT_EXPIRED";

export type PaymentStatus = "PENDING" | "PAID" | "REVIEW" | "FAILED" | "CANCELLED" | "EXPIRED" | "REFUNDED";

/** Hata cevabı (GlobalExceptionHandler). */
export interface ApiError {
    status: number;
    code: string;
    message: string;
    timestamp: IsoInstant;
}

export interface AuthResponse {
    status: string;
    role: Role;
    message: string;
    username: string;
}

export interface ProductResponse {
    id: number;
    name: string;
    description: string | null;
    price: number;
    stock: number;
    imageUrl: string | null;
    origin: string | null;
    brandId: number | null;
    brandName: string | null;
    categoryIds: number[];
    categoryNames: string[];
    testProduct: boolean;
}

export interface ProductRequest {
    name: string;
    description?: string | null;
    price: number | string;
    stock: number;
    imageUrl?: string | null;
    origin?: string | null;
    brandId?: number | null;
    categoryIds?: number[];
    testProduct?: boolean;
}

export interface CategoryResponse {
    id: number;
    name: string;
    parentIds: number[];
}

export interface Brand {
    id: number;
    name: string;
}

export interface CartItemResponse {
    productId: number;
    name: string;
    price: number;
    imageUrl: string | null;
    stock: number;
    quantity: number;
}

export interface OrderItem {
    productId: number;
    productName: string;
    price: number;
    quantity: number;
}

export interface OrderResponse {
    id: number;
    username: string;
    firstName: string;
    lastName: string;
    phone: string;
    city: string;
    district: string;
    neighborhood: string;
    address: string;
    total: number;
    status: OrderStatus;
    createdAt: IsoInstant;
    items: OrderItem[];
}

export interface OrderPageResponse {
    items: OrderResponse[];
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
}

export interface OrderDetailItem {
    productId: number;
    productName: string;
    brand: string | null;
    price: number;
    quantity: number;
    lineTotal: number;
}

export interface OrderDetailResponse {
    id: number;
    createdAt: IsoInstant;
    orderStatus: OrderStatus;
    /** Ödeme kaydı yoksa null. */
    paymentStatus: PaymentStatus | null;
    total: number;
    paidAmount: number;
    firstName: string;
    lastName: string;
    phone: string;
    city: string;
    district: string;
    neighborhood: string;
    address: string;
    items: OrderDetailItem[];
}

export interface RankedItem {
    id: number;
    name: string;
    quantity: number;
    revenue: number;
}

export interface TrendItem {
    id: number;
    name: string;
    recent: number;
    previous: number;
}

export interface OrderStatsResponse {
    trendWindowDays: number;
    topProducts: RankedItem[];
    topCategories: RankedItem[];
    trendingProducts: TrendItem[];
    trendingCategories: TrendItem[];
}

export interface CheckoutCard {
    holderName: string;
    number: string;
    expireMonth: string;
    expireYear: string;
    cvc: string;
}

export interface CheckoutRequest {
    firstName: string;
    lastName: string;
    phone: string;
    email: string;
    identityNumber: string;
    city: string;
    district: string;
    neighborhood: string;
    address: string;
    items: { productId: number; quantity: number }[];
    forceThreeDS: boolean;
    card: CheckoutCard;
}

export interface PayOrderRequest {
    email: string;
    identityNumber: string;
    forceThreeDS: boolean;
    card: CheckoutCard;
}

/** "success" | "review" | "failed" | "threeds" | "processing" */
export type CheckoutResult = "success" | "review" | "failed" | "threeds" | "processing";

export interface CheckoutResponse {
    result: CheckoutResult;
    threeDSHtmlContent: string | null;
    orderId: number | null;
}

/** Ödeme sonucu sorusunun cevabı. */
export type PaymentResult = "processing" | "success" | "review" | "declined" | "failed" | "pending";

export interface PaymentStatusResponse {
    result: PaymentResult;
    /** declined ise kullanıcıya gösterilecek sebep. */
    message: string | null;
}

export interface ProfileResponse {
    username: string;
    role: Role;
    firstName: string | null;
    lastName: string | null;
    phone: string | null;
    city: string | null;
    district: string | null;
    neighborhood: string | null;
    address: string | null;
}

export interface UserDto {
    id: number;
    username: string;
    role: Role;
    active: boolean;
    lastLoginAt: IsoInstant | null;
}

export interface LoginHistoryDto {
    id: number;
    loggedInAt: IsoInstant;
}

export type PaymentAlertKind = "SECOND_PAYMENT" | "LATE_PAYMENT" | "ORPHAN_PAYMENT";

export interface PaymentAlert {
    id: number;
    kind: PaymentAlertKind;
    orderId: number;
    attemptId: string;
    iyzicoPaymentId: string | null;
    amount: number | null;
    refunded: boolean;
    message: string;
    createdAt: IsoInstant;
    resolvedAt: IsoInstant | null;
}

export interface DeadLetterList {
    total: number;
    messages: DeadLetter[];
}

export interface DeadLetter {
    messageId: string;
    type: string;
    orderId: number | null;
    status: string | null;
    amount: string | null;
    error: string | null;
    failedAt: string | null;
    retries: number;
}
