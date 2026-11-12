import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import { Type } from "class-transformer";
import {
  ArrayMaxSize,
  ArrayNotEmpty,
  IsBoolean,
  IsEmail,
  IsInt,
  IsNotEmpty,
  IsNumber,
  IsOptional,
  IsPositive,
  IsString,
  Matches,
  MaxLength,
  Min,
  ValidateNested,
} from "class-validator";
import type { AttemptStatus } from "../attempt/attempt-status.js";

export class BuyerDto {
  @IsString() @IsNotEmpty() @MaxLength(50) id!: string;
  @IsString() @IsNotEmpty() @MaxLength(60) firstName!: string;
  @IsString() @IsNotEmpty() @MaxLength(60) lastName!: string;
  @IsEmail() @MaxLength(254) email!: string;
  @IsString() @IsNotEmpty() @MaxLength(20) phone!: string;
  @Matches(/^\d{11}$/) identityNumber!: string;
  @IsString() @IsNotEmpty() @MaxLength(60) city!: string;
  @IsString() @IsNotEmpty() @MaxLength(300) address!: string;
}

/** Teslimat ve fatura adresi aynı. */
export class AddressDto {
  @IsString() @IsNotEmpty() @MaxLength(130) contactName!: string;
  @IsString() @IsNotEmpty() @MaxLength(60) city!: string;
  @IsString() @IsNotEmpty() @MaxLength(300) address!: string;
}

/** price = birim fiyat × adet. Kalemlerin toplamı amount'a eşit olmalı. */
export class BasketItemDto {
  @IsString() @IsNotEmpty() @MaxLength(50) id!: string;
  @IsString() @IsNotEmpty() @MaxLength(200) name!: string;
  @IsNumber({ maxDecimalPlaces: 2 }) @Min(0.01) price!: number;
}

/** Kart bilgisi yalnızca bellekte yaşar: iyzico'ya iletilir, yazılmaz, loglanmaz. */
export class CardDto {
  @IsString() @IsNotEmpty() @MaxLength(100) holderName!: string;
  @Matches(/^\d{15,19}$/) number!: string;
  @Matches(/^(0[1-9]|1[0-2])$/) expireMonth!: string;
  @Matches(/^\d{4}$/) expireYear!: string;
  @Matches(/^\d{3,4}$/) cvc!: string;

  toString(): string {
    return `Card[**** ${this.number?.slice(-4) ?? ""}]`;
  }
}

/** Backend'in ödeme başlatma isteği. */
export class CreatePaymentRequest {
  @IsInt() @IsPositive() orderId!: number;
  @IsNumber({ maxDecimalPlaces: 2 }) @Min(0.01) amount!: number;
  @ApiPropertyOptional({ description: "true: 3D Secure (SMS) ile öde" })
  @IsOptional() @IsBoolean() threeDS: boolean = false;
  @IsString() @IsNotEmpty() @MaxLength(45) clientIp!: string;
  @ValidateNested() @Type(() => BuyerDto) buyer!: BuyerDto;
  @ValidateNested() @Type(() => AddressDto) address!: AddressDto;
  @ArrayNotEmpty() @ArrayMaxSize(100) @ValidateNested({ each: true }) @Type(() => BasketItemDto) items!: BasketItemDto[];
  @ValidateNested() @Type(() => CardDto) card!: CardDto;
}

/** iyzico'nun 3D dönüş alanları; backend olduğu gibi iletir. */
export class ThreeDSCallback {
  @IsOptional() @IsString() status?: string;
  @IsOptional() @IsString() paymentId?: string;
  @IsOptional() @IsString() conversationData?: string;
  @IsOptional() @IsString() conversationId?: string;
  @IsOptional() @IsString() mdStatus?: string;
  @IsOptional() @IsString() signature?: string;
}

export class RefundRequest {
  @IsString() @IsNotEmpty() @MaxLength(45) clientIp!: string;
}

export class LegacyRefundRequest {
  @IsString() @IsNotEmpty() @MaxLength(50) iyzicoPaymentId!: string;
  @IsNumber({ maxDecimalPlaces: 2 }) @Min(0.01) amount!: number;
  @IsString() @IsNotEmpty() @MaxLength(45) clientIp!: string;
  @IsInt() @IsPositive() orderId!: number;
}

/** Bir denemenin dışarıya gösterilen hâli. Kart verisi içermez. */
export class PaymentAttemptView {
  attemptId!: string;
  orderId!: number;
  @ApiProperty({ enum: ["CREATED", "SENT", "AWAITING_3DS", "CONFIRM_SENT", "SUCCEEDED", "FAILED", "VERIFYING", "REFUNDED"] })
  status!: AttemptStatus;
  amount!: number;
  currency!: string;
  threeDS!: boolean;
  iyzicoPaymentId!: string | null;
  failureReason!: string | null;
  traceId!: string;
  @ApiPropertyOptional({ description: "Yalnızca 3D başlatıldığı cevapta dolu: bankanın SMS sayfası (HTML)" })
  threeDSHtmlContent!: string | null;
}
