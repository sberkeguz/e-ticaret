import { lazy, Suspense } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Login from "./pages/Login";
import Register from "./pages/Register";
import UserHome from "./pages/UserHome";
import ProtectedRoute from "./ProtectedRoute";
import ProductDetail from "./pages/ProductDetail";
import Cart from "./pages/Cart";
import Checkout from "./pages/Checkout";
import Profile from "./pages/Profile";
import MyOrders from "./pages/MyOrders";
import Favorites from "./pages/Favorites";

// Admin sayfaları ayrı dosyalarda: müşteri mağazayı açarken admin kodunu indirmez.
const AdminPanel = lazy(() => import("./pages/AdminPanel"));
const AdminBrand = lazy(() => import("./pages/AdminBrand"));
const AdminUsers = lazy(() => import("./pages/AdminUsers"));
const AdminOrders = lazy(() => import("./pages/AdminOrders"));
const AdminCategory = lazy(() => import("./pages/AdminCategory"));
const AdminProducts = lazy(() => import("./pages/AdminProducts"));
const AdminProductForm = lazy(() => import("./pages/AdminProductForm"));
const AdminLayout = lazy(() => import("./components/AdminLayout"));

export default function App() {
    return (
        <Suspense fallback={<p className="section-caption" style={{ padding: 24 }}>Yükleniyor…</p>}>
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
            <Route path="/favorites" element={<ProtectedRoute><Favorites /></ProtectedRoute>} />

            <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
        </Suspense>
    );
}