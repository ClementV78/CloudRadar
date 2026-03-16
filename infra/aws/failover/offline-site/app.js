const form = document.getElementById("demo-form");
const statusNode = document.getElementById("form-status");
const submitButton = document.getElementById("submit-button");
const lightbox = document.getElementById("preview-lightbox");
const lightboxImage = document.getElementById("lightbox-image");
const lightboxCaption = document.getElementById("lightbox-caption");
const lightboxClose = document.getElementById("lightbox-close");
const previewImages = document.querySelectorAll(".preview-grid img");

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

function openLightbox(imageNode) {
  if (!lightbox || !lightboxImage || !lightboxCaption) {
    return;
  }

  lightboxImage.src = imageNode.src;
  lightboxImage.alt = imageNode.alt;
  lightboxCaption.textContent = imageNode.dataset.caption || imageNode.alt || "";
  lightbox.classList.add("open");
  lightbox.setAttribute("aria-hidden", "false");
  document.body.classList.add("lightbox-open");
}

function closeLightbox() {
  if (!lightbox || !lightboxImage || !lightboxCaption) {
    return;
  }

  lightbox.classList.remove("open");
  lightbox.setAttribute("aria-hidden", "true");
  lightboxImage.src = "";
  lightboxImage.alt = "";
  lightboxCaption.textContent = "";
  document.body.classList.remove("lightbox-open");
}

previewImages.forEach((imageNode) => {
  imageNode.addEventListener("click", () => openLightbox(imageNode));
});

lightboxClose?.addEventListener("click", closeLightbox);

lightbox?.addEventListener("click", (event) => {
  if (event.target === lightbox) {
    closeLightbox();
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && lightbox?.classList.contains("open")) {
    closeLightbox();
  }
});
