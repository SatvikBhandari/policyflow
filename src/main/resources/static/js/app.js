(function () {
  'use strict';

  var body = document.body;

  // Mobile sidebar
  var menuToggle = document.getElementById('menuToggle');
  var overlay = document.getElementById('sidebarOverlay');
  if (menuToggle) {
    menuToggle.addEventListener('click', function () { body.classList.add('sidebar-open'); });
  }
  if (overlay) {
    overlay.addEventListener('click', function () { body.classList.remove('sidebar-open'); });
  }

  // Profile dropdown
  var dropdown = document.getElementById('profileDropdown');
  var toggle = document.getElementById('profileToggle');
  function setOpen(open) {
    if (!dropdown || !toggle) return;
    dropdown.classList.toggle('is-open', open);
    toggle.setAttribute('aria-expanded', String(open));
  }
  if (toggle) {
    toggle.addEventListener('click', function (e) {
      e.stopPropagation();
      setOpen(!dropdown.classList.contains('is-open'));
    });
  }
  document.addEventListener('click', function (e) {
    if (dropdown && !dropdown.contains(e.target)) setOpen(false);
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { setOpen(false); body.classList.remove('sidebar-open'); }
  });

  // Password show/hide and Caps Lock hint (login page)
  var passwordInput = document.getElementById('password');
  var passwordToggle = document.getElementById('togglePassword');
  var capsHint = document.getElementById('capsHint');

  function setPasswordVisible(visible) {
    if (!passwordInput || !passwordToggle) return;
    passwordInput.type = visible ? 'text' : 'password';
    passwordToggle.setAttribute('aria-pressed', String(visible));
    passwordToggle.setAttribute('aria-label', visible ? 'Hide password' : 'Show password');
    passwordToggle.classList.toggle('is-on', visible);
  }

  if (passwordInput && passwordToggle) {
    passwordToggle.addEventListener('click', function () {
      setPasswordVisible(passwordInput.type === 'password');
      passwordInput.focus();
    });

    var updateCapsHint = function (e) {
      if (capsHint && e.getModifierState) {
        capsHint.hidden = !e.getModifierState('CapsLock');
      }
    };
    passwordInput.addEventListener('keydown', updateCapsHint);
    passwordInput.addEventListener('keyup', updateCapsHint);
    passwordInput.addEventListener('blur', function () {
      if (capsHint) capsHint.hidden = true;
    });
  }

  // Submit-once forms: block repeated clicks while the first submit is in flight.
  // The spinner goes on the button that was actually clicked (a form may have two submit buttons).
  var onceForms = document.querySelectorAll('form[data-submit-once]');
  onceForms.forEach(function (form) {
    form.addEventListener('submit', function (e) {
      if (form.dataset.submitted === 'true') {
        e.preventDefault();
        return;
      }
      form.dataset.submitted = 'true';
      var button = e.submitter || form.querySelector('button[type="submit"]');
      if (button) button.classList.add('is-loading');
    });
  });

  // Restore a clean state if the page is shown again from the back/forward cache
  window.addEventListener('pageshow', function (e) {
    if (!e.persisted) return;
    onceForms.forEach(function (form) {
      form.dataset.submitted = 'false';
      form.querySelectorAll('button[type="submit"]').forEach(function (button) {
        button.classList.remove('is-loading');
      });
    });
    setPasswordVisible(false);
  });
})();