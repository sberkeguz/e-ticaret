import { Routes, Route, Navigate } from "react-router-dom";
import Login from "./pages/Login";
import Register from "./pages/Register";
import UserHome from "./pages/UserHome";
import ProtectedRoute from "./ProtectedRoute";
import AdminPanel from "./pages/AdminPanel";
import AdminBrand from "./pages/AdminBrand";
import AdminUsers from "./pages/AdminUsers";
import AdminOrders from "./pages/AdminOrders";
import AdminCategory from "./pages/AdminCategory";
import AdminProducts from "./pages/AdminProducts";
import AdminProductForm from "./pages/AdminProductForm";
import AdminLayout from "./components/AdminLayout";
import ProductDetail from "./pages/ProductDetail";
import Cart from "./pages/Cart";
import Checkout from "./pages/Checkout";
import Profile from "./pages/Profile";
import MyOrders from "./pages/MyOrders";

export default function App() {
    return (
        <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />

            <Route
                path="/admin"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminPanel /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/brand"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminBrand /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            {/* Kategori yönetimi - sadece ADMIN */}
            <Route
                path="/admin/category"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminCategory /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/orders"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminOrders /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/users"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminUsers /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/products"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminProducts /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/products/new"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminProductForm /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/products/:id/edit"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminLayout><AdminProductForm /></AdminLayout>
                    </ProtectedRoute>
                }
            />

            <Route
                path="/home"
                element={
                    <ProtectedRoute>
                        <UserHome />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/product/:id"
                element={
                    <ProtectedRoute>
                        <ProductDetail />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/cart"
                element={
                    <ProtectedRoute>
                        <Cart />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/checkout"
                element={
                    <ProtectedRoute>
                        <Checkout />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/profile"
                element={
                    <ProtectedRoute>
                        <Profile />
                    </ProtectedRoute>
                }
            />

            <Route path="/orders" element={<ProtectedRoute><MyOrders /></ProtectedRoute>} />

            <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
    );
}