using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace CargoService.Data.Migrations
{
    /// <inheritdoc />
    public partial class FakeCarrierTrackingSequence : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateSequence(
                name: "fake_carrier_tracking_seq",
                schema: "cargo");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropSequence(
                name: "fake_carrier_tracking_seq",
                schema: "cargo");
        }
    }
}
