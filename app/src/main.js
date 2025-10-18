document.getElementById('searchBtn').addEventListener('click', searchApartments);
document.getElementById('searchInput').addEventListener('keypress', (e) => {
  if (e.key === 'Enter') searchApartments();
});

async function searchApartments() {
  const query = document.getElementById('searchInput').value.trim();
  if (!query) {
    document.getElementById('results').innerHTML = '<p style="text-align:center;">Please enter a search term 🏙️</p>';
    return;
  }

  const response = await fetch(`/api/apartments?q=${encodeURIComponent(query)}`);
  const data = await response.json();
  displayResults(data);
}

function displayResults(apartments) {
  const results = document.getElementById('results');
  results.innerHTML = '';

  if (apartments.length === 0) {
    results.innerHTML = '<p style="text-align:center;">No apartments found 😔</p>';
    return;
  }

  apartments.forEach(apartment => {
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = `
      <img src="https://source.unsplash.com/400x250/?apartment,interior" alt="Apartment">
      <div class="card-content">
        <h3>${apartment.name}</h3>
        <p><strong>Address:</strong> ${apartment.address}</p>
        <p><strong>Rent:</strong> ${apartment.min_rent} - ${apartment.max_rent}</p>
        <p><strong>Size:</strong> ${apartment.sqft}</p>
        <p><strong>Bed/Bath:</strong> ${apartment.bed} / ${apartment.bath}</p>
        <p><strong>State:</strong> ${apartment.state || 'N/A'}</p>
      </div>
    `;
    results.appendChild(card);
  });
}
