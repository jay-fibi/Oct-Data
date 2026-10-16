const PRODUCTS = [
  { id: "apples", name: "Organic Honeycrisp Apples", category: "Produce", unit: "1 lb bag", price: 3.49, emoji: "🍎" },
  { id: "bananas", name: "Fresh Cavendish Bananas", category: "Produce", unit: "Bunch (~2 lb)", price: 1.59, emoji: "🍌" },
  { id: "avocados", name: "Hass Avocados", category: "Produce", unit: "Pack of 3", price: 4.29, emoji: "🥑" },
  { id: "spinach", name: "Baby Spinach Leaves", category: "Produce", unit: "8 oz tub", price: 2.99, emoji: "🥬" },
  { id: "milk", name: "Whole Pasture-Raised Milk", category: "Dairy", unit: "1 Gallon", price: 4.79, emoji: "🥛" },
  { id: "eggs", name: "Free-Range Brown Eggs", category: "Dairy", unit: "Dozen", price: 3.99, emoji: "🥚" },
  { id: "cheddar", name: "Aged Sharp Cheddar", category: "Dairy", unit: "8 oz block", price: 4.49, emoji: "🧀" },
  { id: "sourdough", name: "Artisan Sourdough Loaf", category: "Bakery", unit: "1 loaf", price: 5.25, emoji: "🍞" },
  { id: "croissants", name: "Butter Croissants", category: "Bakery", unit: "Box of 4", price: 4.99, emoji: "🥐" },
  { id: "oj", name: "Cold-Pressed Orange Juice", category: "Beverages", unit: "52 fl oz", price: 4.89, emoji: "🍊" },
  { id: "coffee", name: "Whole Bean Arabica Coffee", category: "Beverages", unit: "12 oz bag", price: 8.99, emoji: "☕" },
  { id: "olive-oil", name: "Extra Virgin Olive Oil", category: "Pantry", unit: "500 ml bottle", price: 9.49, emoji: "🫒" }
];

const FREE_DELIVERY_THRESHOLD = 35;
const DELIVERY_FEE = 4.99;

const state = {
  selectedCategory: "all",
  searchQuery: "",
  cart: {}
};

const productGridEl = document.getElementById("product-grid");
const emptyCatalogMsgEl = document.getElementById("empty-catalog-msg");
const categoryFiltersEl = document.getElementById("category-filters");
const searchInputEl = document.getElementById("search-input");
const cartCountEl = document.getElementById("cart-count");
const cartDrawerEl = document.getElementById("cart-drawer");
const cartBackdropEl = document.getElementById("cart-backdrop");
const cartToggleBtn = document.getElementById("cart-toggle-btn");
const cartCloseBtn = document.getElementById("cart-close-btn");
const cartItemsEl = document.getElementById("cart-items");
const cartSubtotalEl = document.getElementById("cart-subtotal");
const cartDeliveryEl = document.getElementById("cart-delivery");
const cartTotalEl = document.getElementById("cart-total");
const clearCartBtn = document.getElementById("clear-cart-btn");
const checkoutBtn = document.getElementById("checkout-btn");
const checkoutStatusEl = document.getElementById("checkout-status");

function formatCurrency(amount) {
  return `$${amount.toFixed(2)}`;
}

function getFilteredProducts() {
  return PRODUCTS.filter((product) => {
    const matchesCategory =
      state.selectedCategory === "all" || product.category === state.selectedCategory;
    const query = state.searchQuery.trim().toLowerCase();
    const matchesSearch =
      !query ||
      product.name.toLowerCase().includes(query) ||
      product.category.toLowerCase().includes(query);
    return matchesCategory && matchesSearch;
  });
}

function renderProducts() {
  const filtered = getFilteredProducts();
  productGridEl.innerHTML = "";

  if (filtered.length === 0) {
    emptyCatalogMsgEl.hidden = false;
    return;
  }

  emptyCatalogMsgEl.hidden = true;

  filtered.forEach((product) => {
    const card = document.createElement("article");
    card.className = "product-card";
    card.innerHTML = `
      <div>
        <div class="product-emoji" aria-hidden="true">${product.emoji}</div>
        <span class="product-category">${product.category}</span>
        <h3 class="product-name">${product.name}</h3>
        <p class="product-unit">${product.unit}</p>
      </div>
      <div class="product-footer">
        <span class="product-price">${formatCurrency(product.price)}</span>
        <button class="add-btn" type="button" data-add-id="${product.id}">+ Add</button>
      </div>
    `;
    productGridEl.appendChild(card);
  });
}


function updateQuantity(productId, delta) {
  const current = state.cart[productId] || 0;
  const next = current + delta;
  if (next <= 0) {
    delete state.cart[productId];
  } else {
    state.cart[productId] = next;
  }
  checkoutStatusEl.textContent = "";
  renderCart();
}

function renderCart() {
  const entries = Object.entries(state.cart);
  const totalCount = entries.reduce((sum, [, qty]) => sum + qty, 0);
  cartCountEl.textContent = String(totalCount);

  if (entries.length === 0) {
    cartItemsEl.innerHTML = '<p class="cart-empty">Your cart is empty. Add some fresh items!</p>';
    cartSubtotalEl.textContent = formatCurrency(0);
    cartDeliveryEl.textContent = formatCurrency(0);
    cartTotalEl.textContent = formatCurrency(0);
    return;
  }

  cartItemsEl.innerHTML = "";
  let subtotal = 0;

  entries.forEach(([productId, qty]) => {
    const product = PRODUCTS.find((item) => item.id === productId);
    if (!product) return;

    const lineTotal = product.price * qty;
    subtotal += lineTotal;

    const row = document.createElement("div");
    row.className = "cart-item";
    row.innerHTML = `
      <div class="cart-item-info">
        <strong>${product.emoji} ${product.name}</strong>
        <span>${formatCurrency(product.price)} × ${qty} = ${formatCurrency(lineTotal)}</span>
      </div>
      <div class="cart-item-controls">
        <button class="qty-btn" type="button" data-qty-id="${product.id}" data-delta="-1" aria-label="Decrease quantity">−</button>
        <span>${qty}</span>
        <button class="qty-btn" type="button" data-qty-id="${product.id}" data-delta="1" aria-label="Increase quantity">+</button>
      </div>
    `;
    cartItemsEl.appendChild(row);
  });

  const delivery = subtotal >= FREE_DELIVERY_THRESHOLD ? 0 : DELIVERY_FEE;
  const total = subtotal + delivery;

  cartSubtotalEl.textContent = formatCurrency(subtotal);
  cartDeliveryEl.textContent = delivery === 0 ? "FREE" : formatCurrency(delivery);
  cartTotalEl.textContent = formatCurrency(total);
}

function setCartOpen(isOpen) {
  cartDrawerEl.classList.toggle("open", isOpen);
  cartDrawerEl.setAttribute("aria-hidden", String(!isOpen));
  cartToggleBtn.setAttribute("aria-expanded", String(isOpen));
  cartBackdropEl.hidden = !isOpen;
}

productGridEl.addEventListener("click", (event) => {
  const button = event.target.closest("[data-add-id]");
  if (!button) return;
  updateQuantity(button.getAttribute("data-add-id"), 1);
});

cartItemsEl.addEventListener("click", (event) => {
  const button = event.target.closest("[data-qty-id]");
  if (!button) return;
  updateQuantity(button.getAttribute("data-qty-id"), Number(button.getAttribute("data-delta")));
});

categoryFiltersEl.addEventListener("click", (event) => {
  const button = event.target.closest("[data-category]");
  if (!button) return;
  state.selectedCategory = button.getAttribute("data-category");
  categoryFiltersEl.querySelectorAll(".filter-btn").forEach((btn) => {
    btn.classList.toggle("active", btn === button);
  });
  renderProducts();
});

searchInputEl.addEventListener("input", (event) => {
  state.searchQuery = event.target.value;
  renderProducts();
});

cartToggleBtn.addEventListener("click", () => setCartOpen(true));
cartCloseBtn.addEventListener("click", () => setCartOpen(false));
cartBackdropEl.addEventListener("click", () => setCartOpen(false));

clearCartBtn.addEventListener("click", () => {
  state.cart = {};
  checkoutStatusEl.textContent = "";
  renderCart();
});

checkoutBtn.addEventListener("click", () => {
  const itemCount = Object.values(state.cart).reduce((sum, qty) => sum + qty, 0);
  if (itemCount === 0) {
    checkoutStatusEl.textContent = "Add items to your cart before checking out.";
    return;
  }
  checkoutStatusEl.textContent = `Order placed for ${itemCount} item(s)! Thank you for shopping at FreshMart.`;
  state.cart = {};
  renderCart();
});

renderProducts();
renderCart();
