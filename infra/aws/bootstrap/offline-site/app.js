const form = document.getElementById("demo-form");
const statusNode = document.getElementById("form-status");
const submitButton = document.getElementById("submit-button");

function setStatus(message, type) {
  statusNode.textContent = message;
  statusNode.classList.remove("success", "error");
  if (type) {
    statusNode.classList.add(type);
  }
}

function sanitize(input) {
  return String(input || "").trim();
}

function validate(payload) {
  if (payload.honeypot) {
    return "Invalid request.";
  }
  if (payload.name.length < 2 || payload.name.length > 80) {
    return "Please provide a valid name.";
  }
  if (payload.email.length < 5 || payload.email.length > 254 || !payload.email.includes("@")) {
    return "Please provide a valid email address.";
  }
  if (payload.message.length < 10 || payload.message.length > 2000) {
    return "Message must be between 10 and 2000 characters.";
  }
  return null;
}

async function submitRequest(payload) {
  const response = await fetch("/api/contact-demo", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  let body = null;
  try {
    body = await response.json();
  } catch {
    body = null;
  }

  if (!response.ok) {
    const message = body?.error || "Request failed. Please try again.";
    throw new Error(message);
  }
}

form?.addEventListener("submit", async (event) => {
  event.preventDefault();

  const payload = {
    name: sanitize(document.getElementById("name")?.value),
    email: sanitize(document.getElementById("email")?.value),
    message: sanitize(document.getElementById("message")?.value),
    honeypot: sanitize(document.getElementById("website")?.value),
    page: window.location.href,
    userAgent: navigator.userAgent
  };

  const validationError = validate(payload);
  if (validationError) {
    setStatus(validationError, "error");
    return;
  }

  submitButton.disabled = true;
  setStatus("Sending request...", null);

  try {
    await submitRequest(payload);
    form.reset();
    setStatus("Demo request sent. Thank you, I will get back to you soon.", "success");
  } catch (error) {
    setStatus(error.message || "Request failed. Please try again.", "error");
  } finally {
    submitButton.disabled = false;
  }
});
