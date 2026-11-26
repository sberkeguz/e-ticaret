using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace CargoService.Data.Migrations
{
    /// <inheritdoc />
    public partial class ShipmentParcel : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "parcel",
                schema: "cargo",
                table: "shipments",
                type: "jsonb",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "parcel",
                schema: "cargo",
                table: "shipments");
        }
    }
}
