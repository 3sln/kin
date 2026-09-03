package gcd

// kin:begin kin/gcd.kin
func Gcd(a int, b int) int {
	// Euclid's algorithm, by repeated remainder.
	x := a
	y := b
	for y != 0 {
		t := x % y
		x = y
		y = t
	}
	return x
}

// kin:end kin/gcd.kin
