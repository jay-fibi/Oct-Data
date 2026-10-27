"use strict";

const greetingButton = document.querySelector("#greeting-button");
const message = document.querySelector("#message");
const initialMessage = message.textContent;
let isGreetingVisible = false;

greetingButton.setAttribute("aria-pressed", "false");

greetingButton.addEventListener("click", () => {
  isGreetingVisible = !isGreetingVisible;
  message.textContent = isGreetingVisible
    ? "Hello, Jay! Your JavaScript is working."
    : initialMessage;
  greetingButton.setAttribute("aria-pressed", String(isGreetingVisible));
});